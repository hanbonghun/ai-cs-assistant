package com.aicsassistant.analysis.infra.vector;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class PgvectorRowMapper implements RowMapper<RetrievedManualChunkDto> {

    @Override
    public RetrievedManualChunkDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RetrievedManualChunkDto(
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
}
