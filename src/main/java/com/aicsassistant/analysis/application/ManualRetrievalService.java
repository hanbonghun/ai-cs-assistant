package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.dto.ScoredManualChunk;
import com.aicsassistant.analysis.infra.ManualChunkRetrievalRepository;
import com.aicsassistant.analysis.infra.llm.EmbeddingClient;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
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

    private final ManualChunkRetrievalRepository retrievalRepository;
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
                if (!retrievalRepository.hasActiveEmbeddings()) {
                    return new RetrievalOutcome(retrievalRepository.findFallbackTopK(DEFAULT_TOP_K), "fallback_no_embeddings");
                }
                return new RetrievalOutcome(findByHybrid(queryEmbedding, inquiryContent), "hybrid");
            } catch (DataAccessException ignored) {
                // Fallback path is used when vector dimensions/data are not ready.
            }
        }
        return new RetrievalOutcome(retrievalRepository.findFallbackTopK(DEFAULT_TOP_K), "fallback_query_error");
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
        List<ScoredManualChunk> vectorCandidates = retrievalRepository.findVectorCandidates(queryEmbedding, FUSION_CANDIDATE_K);
        List<ScoredManualChunk> keywordCandidates = findKeywordCandidatesPreprocessed(query);

        Map<Long, FusionEntry> byId = new LinkedHashMap<>();
        for (int i = 0; i < vectorCandidates.size(); i++) {
            ScoredManualChunk c = vectorCandidates.get(i);
            FusionEntry entry = byId.computeIfAbsent(c.chunk().id(), k -> new FusionEntry(c.chunk()));
            entry.vectorRank = i + 1;
            entry.vectorScore = c.score();
        }
        for (int i = 0; i < keywordCandidates.size(); i++) {
            ScoredManualChunk c = keywordCandidates.get(i);
            FusionEntry entry = byId.computeIfAbsent(c.chunk().id(), k -> new FusionEntry(c.chunk()));
            entry.keywordRank = i + 1;
            entry.keywordScore = c.score();
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

    private List<ScoredManualChunk> findKeywordCandidatesPreprocessed(String query) {
        String cleaned = KoreanQueryPreprocessor.forKeywordSearch(query);
        if (cleaned.isBlank()) {
            return List.of();
        }
        return retrievalRepository.findKeywordCandidates(cleaned, MIN_KEYWORD_SCORE, FUSION_CANDIDATE_K);
    }

    private void augmentVectorScores(Map<Long, FusionEntry> byId, List<Double> queryEmbedding) {
        List<Long> missingIds = byId.values().stream()
                .filter(e -> e.vectorRank == 0)
                .map(e -> e.chunk.id())
                .toList();
        if (missingIds.isEmpty()) {
            return;
        }

        Map<Long, Double> scores = retrievalRepository.findVectorScoresByIds(missingIds, queryEmbedding);
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
