package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.infra.llm.EmbeddingClient;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManualRetrievalService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int FUSION_CANDIDATE_K = 20;
    private static final double MIN_SIMILARITY_SCORE = 0.75;
    private static final double MIN_KEYWORD_SCORE = 0.18;
    private static final double MIN_VECTOR_FLOOR_FOR_KEYWORD = 0.5;
    private static final int RRF_K = 60;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;

    public List<RetrievedManualChunkDto> retrieve(String inquiryContent) {
        List<Double> queryEmbedding = embeddingClient.embed(inquiryContent);
        if (queryEmbedding != null && !queryEmbedding.isEmpty()) {
            try {
                if (!hasActiveEmbeddings()) {
                    return findFallback();
                }
                return findByHybrid(queryEmbedding, inquiryContent);
            } catch (DataAccessException ignored) {
                // Fallback path is used when vector dimensions/data are not ready.
            }
        }
        return findFallback();
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
                  and word_similarity(cast(? as text), mc.content) >= ?
                order by word_similarity(cast(? as text), mc.content) desc, mc.id
                limit ?
                """, scoredChunkRowMapper(), cleaned, cleaned, MIN_KEYWORD_SCORE, cleaned, limit);
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
