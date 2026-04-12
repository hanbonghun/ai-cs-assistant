package com.aicsassistant.manual.infra;

import com.aicsassistant.manual.application.ChunkWithEmbedding;
import com.aicsassistant.manual.dto.ManualChunkResponse;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ManualChunkJdbcRepository {

    private static final RowMapper<ManualChunkResponse> CHUNK_ROW_MAPPER = (rs, rowNum) -> new ManualChunkResponse(
            rs.getLong("id"),
            rs.getLong("manual_document_id"),
            rs.getInt("chunk_index"),
            rs.getInt("document_version"),
            rs.getString("content"),
            rs.getInt("token_count"),
            rs.getBoolean("active"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbcTemplate;

    public void replaceActiveChunks(Long documentId, int version, List<ChunkWithEmbedding> chunks) {
        jdbcTemplate.update("update manual_chunk set active = false where manual_document_id = ?", documentId);

        for (int i = 0; i < chunks.size(); i++) {
            ChunkWithEmbedding chunk = chunks.get(i);
            String vectorLiteral = chunk.toVectorLiteral();

            if (vectorLiteral != null) {
                jdbcTemplate.update("""
                        insert into manual_chunk (
                            manual_document_id, chunk_index, document_version,
                            content, token_count, embedding, active, created_at
                        ) values (?, ?, ?, ?, ?, cast(? as vector), ?, ?)
                        """,
                        documentId, i, version,
                        chunk.content(), estimateTokenCount(chunk.content()),
                        vectorLiteral, true, Timestamp.valueOf(LocalDateTime.now())
                );
            } else {
                jdbcTemplate.update("""
                        insert into manual_chunk (
                            manual_document_id, chunk_index, document_version,
                            content, token_count, embedding, active, created_at
                        ) values (?, ?, ?, ?, ?, null, ?, ?)
                        """,
                        documentId, i, version,
                        chunk.content(), estimateTokenCount(chunk.content()),
                        true, Timestamp.valueOf(LocalDateTime.now())
                );
            }
        }
    }

    public void deactivateManual(Long documentId) {
        jdbcTemplate.update("update manual_chunk set active = false where manual_document_id = ?", documentId);
    }

    public List<ManualChunkResponse> findActiveChunks() {
        return jdbcTemplate.query("""
                select mc.*
                from manual_chunk mc
                join manual_document md on md.id = mc.manual_document_id
                where md.active = true
                  and mc.active = true
                order by mc.manual_document_id, mc.chunk_index
                """, CHUNK_ROW_MAPPER);
    }

    public List<ManualChunkResponse> findActiveChunksByDocumentId(Long documentId) {
        return jdbcTemplate.query("""
                select mc.*
                from manual_chunk mc
                join manual_document md on md.id = mc.manual_document_id
                where md.id = ?
                  and md.active = true
                  and mc.active = true
                order by mc.chunk_index
                """, CHUNK_ROW_MAPPER, documentId);
    }

    public List<com.aicsassistant.ui.viewmodel.InquiryDetailViewModel.EvidenceChunkView> findEvidenceChunksByIds(List<Long> chunkIds) {
        if (chunkIds.isEmpty()) return List.of();
        String placeholders = chunkIds.stream().map(id -> "?").reduce((a, b) -> a + ", " + b).orElse("?");
        String sql = """
                select mc.id, mc.manual_document_id, md.title as manual_document_title,
                       md.category as manual_document_category, mc.chunk_index,
                       mc.document_version, mc.token_count, mc.content
                from manual_chunk mc
                join manual_document md on md.id = mc.manual_document_id
                where mc.id in (%s)
                order by mc.id
                """.formatted(placeholders);
        return jdbcTemplate.query(sql, chunkIds.toArray(),
                (rs, rowNum) -> new com.aicsassistant.ui.viewmodel.InquiryDetailViewModel.EvidenceChunkView(
                        rs.getLong("id"),
                        rs.getLong("manual_document_id"),
                        rs.getString("manual_document_title"),
                        rs.getString("manual_document_category"),
                        rs.getInt("chunk_index"),
                        rs.getInt("document_version"),
                        rs.getInt("token_count"),
                        rs.getString("content")
                ));
    }

    private int estimateTokenCount(String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }
}
