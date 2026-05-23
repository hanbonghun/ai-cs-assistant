package com.aicsassistant.analysis.infra;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.dto.ScoredManualChunk;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RAG 검색 전용 read-only repository.
 *
 * <p>pgvector(`&lt;=&gt;`)와 pg_trgm(`&lt;%`, `&lt;&lt;-&gt;`) PostgreSQL 확장 연산자를 사용하므로
 * raw SQL을 JdbcTemplate으로 직접 실행한다. 호출자는 service 레이어에서
 * {@code @Transactional(readOnly = true)} 안에서 호출해야 한다 — keyword 검색은
 * {@code SET LOCAL pg_trgm.word_similarity_threshold}에 의존하기 때문.
 */
@Repository
@RequiredArgsConstructor
public class ManualChunkRetrievalRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean hasActiveEmbeddings() {
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

    public List<ScoredManualChunk> findVectorCandidates(List<Double> queryEmbedding, int limit) {
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

    /**
     * pg_trgm 기반 키워드 후보 검색. cleanedQuery는 호출자가 비즈니스 전처리를 마친 텍스트여야 한다.
     *
     * <p>{@code SET LOCAL pg_trgm.word_similarity_threshold}는 호출 트랜잭션 스코프 + 동일 connection
     * 안에서만 유효하므로 {@link Propagation#MANDATORY}로 호출자 트랜잭션을 강제한다 — 트랜잭션 없이
     * 호출되면 {@code execute(SET LOCAL)}과 후속 {@code query}가 서로 다른 connection을 잡아 임계값이
     * silent하게 무력화되는 사고를 막는다. {@code <%} operator의 임계값과 {@code <<->} distance 정렬이
     * 함께 동작한다 ({@code gin_trgm_ops} GIN 인덱스 활용).
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<ScoredManualChunk> findKeywordCandidates(String cleanedQuery, double threshold, int limit) {
        jdbcTemplate.execute("set local pg_trgm.word_similarity_threshold = " + threshold);
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
                """, scoredChunkRowMapper(), cleanedQuery, cleanedQuery, cleanedQuery, limit);
    }

    /**
     * 주어진 chunk id들에 대한 실제 vector cosine similarity를 한 번에 계산한다.
     * keyword 후보가 vector top-K 밖일 때 hybrid gate가 vectorScore 0.0으로 탈락시키는 것을 막기 위함.
     */
    public Map<Long, Double> findVectorScoresByIds(List<Long> ids, List<Double> queryEmbedding) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        Object[] params = new Object[ids.size() + 1];
        params[0] = vectorLiteral;
        for (int i = 0; i < ids.size(); i++) {
            params[i + 1] = ids.get(i);
        }

        Map<Long, Double> scores = new HashMap<>();
        jdbcTemplate.query("""
                select id, (1 - (embedding <=> cast(? as vector))) as score
                from manual_chunk
                where id in (%s) and embedding is not null
                """.formatted(placeholders),
                rs -> { scores.put(rs.getLong("id"), rs.getDouble("score")); },
                params);
        return scores;
    }

    public List<RetrievedManualChunkDto> findFallbackTopK(int limit) {
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
                """, fallbackRowMapper(), limit);
    }

    private RowMapper<ScoredManualChunk> scoredChunkRowMapper() {
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
            return new ScoredManualChunk(chunk, rs.getDouble("score"));
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

    private String toVectorLiteral(List<Double> vector) {
        return vector.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
