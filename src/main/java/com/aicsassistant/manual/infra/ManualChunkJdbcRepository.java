package com.aicsassistant.manual.infra;

import com.aicsassistant.manual.dto.ManualChunkResponse;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
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

    public ManualChunkJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void replaceActiveChunks(Long documentId, int version, List<String> contents) {
        jdbcTemplate.update("update manual_chunk set active = false where manual_document_id = ?", documentId);

        String insertSql = """
                insert into manual_chunk (
                    manual_document_id,
                    chunk_index,
                    document_version,
                    content,
                    token_count,
                    embedding,
                    active,
                    created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        for (int i = 0; i < contents.size(); i++) {
            String content = contents.get(i);
            jdbcTemplate.update(
                    insertSql,
                    documentId,
                    i,
                    version,
                    content,
                    estimateTokenCount(content),
                    null,
                    true,
                    Timestamp.valueOf(LocalDateTime.now())
            );
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

    private int estimateTokenCount(String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }
}
