package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.infra.llm.EmbeddingClient;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManualRetrievalService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int FUSION_CANDIDATE_K = 20;
    private static final double MIN_SIMILARITY_SCORE = 0.75;
    private static final double MIN_KEYWORD_SCORE = 0.18;
    private static final double MIN_VECTOR_FLOOR_FOR_KEYWORD = 0.5;
    private static final int RRF_K = 60;

    private static final AttributeKey<String> ATTR_LF_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    private static final AttributeKey<String> ATTR_LF_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    private static final AttributeKey<Long> ATTR_RESULT_COUNT = AttributeKey.longKey("retrieval.result_count");
    private static final AttributeKey<String> ATTR_PATH = AttributeKey.stringKey("retrieval.path");

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final Tracer tracer;

    @Transactional(readOnly = true)
    public List<RetrievedManualChunkDto> retrieve(String inquiryContent) {
        Span span = tracer.spanBuilder("rag.retrieve")
                .setAttribute(ATTR_LF_INPUT, inquiryContent)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            RetrievalOutcome outcome = doRetrieveWithPath(inquiryContent);
            span.setAttribute(ATTR_PATH, outcome.path());
            span.setAttribute(ATTR_RESULT_COUNT, (long) outcome.chunks().size());
            span.setAttribute(ATTR_LF_OUTPUT, summarizeChunks(outcome.chunks()));
            return outcome.chunks();
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private RetrievalOutcome doRetrieveWithPath(String inquiryContent) {
        List<Double> queryEmbedding = embeddingClient.embed(inquiryContent);
        if (queryEmbedding != null && !queryEmbedding.isEmpty()) {
            try {
                if (!hasActiveEmbeddings()) {
                    return new RetrievalOutcome(findFallback(), "fallback_no_embeddings");
                }
                return new RetrievalOutcome(findByHybrid(queryEmbedding, inquiryContent), "hybrid");
            } catch (DataAccessException ignored) {
                // Fallback path is used when vector dimensions/data are not ready.
            }
        }
        return new RetrievalOutcome(findFallback(), "fallback_query_error");
    }

    private record RetrievalOutcome(List<RetrievedManualChunkDto> chunks, String path) {
    }

    private String summarizeChunks(List<RetrievedManualChunkDto> chunks) {
        if (chunks.isEmpty()) {
            return "[]";
        }
        return chunks.stream()
                .map(c -> String.format("{title=%s, vsim=%s, ksim=%s}",
                        c.manualDocumentTitle(),
                        c.similarityScore() == null ? "-" : String.format("%.3f", c.similarityScore()),
                        c.keywordScore() == null ? "-" : String.format("%.3f", c.keywordScore())))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private List<RetrievedManualChunkDto> findByHybrid(List<Double> queryEmbedding, String query) {
        List<Candidate> vectorCandidates = findVectorCandidates(queryEmbedding, FUSION_CANDIDATE_K);
        List<Candidate> keywordCandidates = findKeywordCandidates(query, FUSION_CANDIDATE_K);

        Map<Long, FusionEntry> byId = new LinkedHashMap<>();
        for (int i = 0; i < vectorCandidates.size(); i++) {
            Candidate c = vectorCandidates.get(i);
            FusionEntry entry = byId.computeIfAbsent(c.chunk.id(), k -> new FusionEntry(c.chunk));
            entry.vectorRank = i + 1;
            entry.vectorScore = c.score;
        }
        for (int i = 0; i < keywordCandidates.size(); i++) {
            Candidate c = keywordCandidates.get(i);
            FusionEntry entry = byId.computeIfAbsent(c.chunk.id(), k -> new FusionEntry(c.chunk));
            entry.keywordRank = i + 1;
            entry.keywordScore = c.score;
        }

        // P1 수정: keyword 후보가 vector top-K 밖이면 vectorScore가 기본값 0.0이라
        // gate(vector >= 0.5)에서 자동 탈락한다. union된 id들의 실제 vector score를 보강.
        augmentVectorScores(byId, queryEmbedding);

        return byId.values().stream()
                .filter(this::passesHybridGate)
                .sorted(Comparator.comparingDouble(FusionEntry::rrfScore).reversed())
                .limit(DEFAULT_TOP_K)
                .map(FusionEntry::toDto)
                .toList();
    }

    private List<Candidate> findVectorCandidates(List<Double> queryEmbedding, int limit) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        return jdbcTemplate.query("""
                select
                    mc.id,
                    mc.manual_document_id,
                    md.title as manual_document_title,
                    md.category as manual_document_category,
                    mc.chunk_index,
                    mc.document_version,
                    mc.token_count,
                    mc.content,
                    (1 - (mc.embedding <=> cast(? as vector))) as score
                from manual_chunk mc
                join manual_document md on md.id = mc.manual_document_id
                where md.active = true
                  and mc.active = true
                  and mc.embedding is not null
                order by mc.embedding <=> cast(? as vector), mc.id
                limit ?
                """, scoredChunkRowMapper(), vectorLiteral, vectorLiteral, limit);
    }

    private List<Candidate> findKeywordCandidates(String query, int limit) {
        String cleaned = KoreanQueryPreprocessor.forKeywordSearch(query);
        if (cleaned.isBlank()) {
            return List.of();
        }
        // P2 수정: <% operator + <<-> distance를 써서 gin_trgm_ops GIN 인덱스 활용.
        // <% operator의 임계값은 pg_trgm.word_similarity_threshold GUC로 제어.
        // SET LOCAL은 트랜잭션 스코프에서만 유효하므로 retrieve()가 @Transactional이어야 한다.
        jdbcTemplate.execute("set local pg_trgm.word_similarity_threshold = " + MIN_KEYWORD_SCORE);
        return jdbcTemplate.query("""
                select
                    mc.id,
                    mc.manual_document_id,
                    md.title as manual_document_title,
                    md.category as manual_document_category,
                    mc.chunk_index,
                    mc.document_version,
                    mc.token_count,
                    mc.content,
                    word_similarity(cast(? as text), mc.content) as score
                from manual_chunk mc
                join manual_document md on md.id = mc.manual_document_id
                where md.active = true
                  and mc.active = true
                  and cast(? as text) <% mc.content
                order by cast(? as text) <<-> mc.content, mc.id
                limit ?
                """, scoredChunkRowMapper(), cleaned, cleaned, cleaned, limit);
    }

    private void augmentVectorScores(Map<Long, FusionEntry> byId, List<Double> queryEmbedding) {
        List<Long> missingIds = byId.values().stream()
                .filter(e -> e.vectorRank == 0)
                .map(e -> e.chunk.id())
                .toList();
        if (missingIds.isEmpty()) {
            return;
        }

        String idCsv = missingIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        Map<Long, Double> scores = new HashMap<>();
        jdbcTemplate.query("""
                select id, (1 - (embedding <=> cast(? as vector))) as score
                from manual_chunk
                where id in (%s) and embedding is not null
                """.formatted(idCsv),
                rs -> { scores.put(rs.getLong("id"), rs.getDouble("score")); },
                vectorLiteral);

        for (FusionEntry entry : byId.values()) {
            Double score = scores.get(entry.chunk.id());
            if (score != null) {
                entry.vectorScore = score;
            }
        }
    }

    private boolean passesHybridGate(FusionEntry e) {
        if (e.vectorScore >= MIN_SIMILARITY_SCORE) {
            return true;
        }
        return e.keywordScore >= MIN_KEYWORD_SCORE
                && e.vectorScore >= MIN_VECTOR_FLOOR_FOR_KEYWORD;
    }

    private boolean hasActiveEmbeddings() {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists (
                    select 1
                    from manual_chunk mc
                    join manual_document md on md.id = mc.manual_document_id
                    where md.active = true
                      and mc.active = true
                      and mc.embedding is not null
                )
                """, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private List<RetrievedManualChunkDto> findFallback() {
        return jdbcTemplate.query("""
                select
                    mc.id,
                    mc.manual_document_id,
                    md.title as manual_document_title,
                    md.category as manual_document_category,
                    mc.chunk_index,
                    mc.document_version,
                    mc.token_count,
                    mc.content
                from manual_chunk mc
                join manual_document md on md.id = mc.manual_document_id
                where md.active = true
                  and mc.active = true
                order by mc.id, mc.chunk_index
                limit ?
                """, fallbackRowMapper(), DEFAULT_TOP_K);
    }

    private String toVectorLiteral(List<Double> vector) {
        return vector.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private RowMapper<Candidate> scoredChunkRowMapper() {
        return (rs, rowNum) -> {
            RetrievedManualChunkDto chunk = new RetrievedManualChunkDto(
                    rs.getLong("id"),
                    rs.getLong("manual_document_id"),
                    rs.getString("manual_document_title"),
                    rs.getString("manual_document_category"),
                    rs.getInt("chunk_index"),
                    rs.getInt("document_version"),
                    rs.getInt("token_count"),
                    rs.getString("content")
            );
            return new Candidate(chunk, rs.getDouble("score"));
        };
    }

    private RowMapper<RetrievedManualChunkDto> fallbackRowMapper() {
        return (rs, rowNum) -> new RetrievedManualChunkDto(
                rs.getLong("id"),
                rs.getLong("manual_document_id"),
                rs.getString("manual_document_title"),
                rs.getString("manual_document_category"),
                rs.getInt("chunk_index"),
                rs.getInt("document_version"),
                rs.getInt("token_count"),
                rs.getString("content")
        );
    }

    private record Candidate(RetrievedManualChunkDto chunk, double score) {
    }

    private static final class FusionEntry {
        private final RetrievedManualChunkDto chunk;
        private int vectorRank = 0;
        private int keywordRank = 0;
        private double vectorScore = 0.0;
        private double keywordScore = 0.0;

        private FusionEntry(RetrievedManualChunkDto chunk) {
            this.chunk = chunk;
        }

        double rrfScore() {
            double total = 0.0;
            if (vectorRank > 0) {
                total += 1.0 / (RRF_K + vectorRank);
            }
            if (keywordRank > 0) {
                total += 1.0 / (RRF_K + keywordRank);
            }
            return total;
        }

        RetrievedManualChunkDto toDto() {
            return new RetrievedManualChunkDto(
                    chunk.id(),
                    chunk.manualDocumentId(),
                    chunk.manualDocumentTitle(),
                    chunk.manualCategory(),
                    chunk.chunkIndex(),
                    chunk.documentVersion(),
                    chunk.tokenCount(),
                    chunk.content(),
                    vectorRank > 0 ? vectorScore : null,
                    keywordRank > 0 ? keywordScore : null
            );
        }
    }
}
