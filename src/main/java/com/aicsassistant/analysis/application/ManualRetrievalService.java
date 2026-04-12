package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.infra.llm.EmbeddingClient;
import com.aicsassistant.analysis.infra.vector.PgvectorRowMapper;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManualRetrievalService {

    private static final int DEFAULT_TOP_K = 5;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final PgvectorRowMapper pgvectorRowMapper;

    public List<RetrievedManualChunkDto> retrieve(String inquiryContent) {
        List<Double> queryEmbedding = embeddingClient.embed(inquiryContent);
        if (queryEmbedding != null && !queryEmbedding.isEmpty()) {
            try {
                List<RetrievedManualChunkDto> vectorResults = findByVector(queryEmbedding);
                if (!vectorResults.isEmpty()) {
                    return vectorResults;
                }
            } catch (DataAccessException ignored) {
                // Fallback path is used when vector dimensions/data are not ready.
            }
        }
        return findFallback();
    }

    private List<RetrievedManualChunkDto> findByVector(List<Double> queryEmbedding) {
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
                    mc.content
                from manual_chunk mc
                join manual_document md on md.id = mc.manual_document_id
                where md.active = true
                  and mc.active = true
                  and mc.embedding is not null
                order by mc.embedding <=> cast(? as vector), mc.id
                limit ?
                """, pgvectorRowMapper, vectorLiteral, DEFAULT_TOP_K);
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
                """, pgvectorRowMapper, DEFAULT_TOP_K);
    }

    private String toVectorLiteral(List<Double> vector) {
        return vector.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
