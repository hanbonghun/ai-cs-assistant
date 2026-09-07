package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.infra.llm.EmbeddingClient;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RAG 검색의 트랜잭션 경계 회귀 테스트 — 임베딩 API 왕복 동안 DB 커넥션을 쥐고 있지 않는다.
 * 외부 호출이 느려지면 그만큼 풀이 마른다.
 */
@SpringBootTest(properties = "app.ai.api-key=test-key")
@Import(ManualRetrievalTransactionBoundaryTest.StubEmbeddingConfig.class)
class ManualRetrievalTransactionBoundaryTest extends PostgresVectorIntegrationTest {

    private static final int EMBEDDING_DIMENSIONS = 1536;

    static final AtomicInteger ACTIVE_CONNECTIONS_DURING_EMBED = new AtomicInteger(-1);

    @Autowired
    ManualRetrievalService manualRetrievalService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void seedAndReset() {
        seedManualDocument(1L, "환불 정책", "REFUND", "환불은 수령 후 7일 이내 신청할 수 있습니다.");
        ACTIVE_CONNECTIONS_DURING_EMBED.set(-1);
    }

    @Test
    @DisplayName("임베딩 API 호출 동안 DB 커넥션을 점유하지 않는다")
    void doesNotHoldConnectionDuringEmbedding() {
        manualRetrievalService.retrieve("환불 언제까지 되나요");

        assertThat(ACTIVE_CONNECTIONS_DURING_EMBED).hasValue(0);
    }

    private void seedManualDocument(Long id, String title, String category, String content) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                insert into manual_document (id, title, category, content, version, active, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, title, category, content, 1, true, now, now);
        jdbcTemplate.update("""
                insert into manual_chunk (
                    id, manual_document_id, chunk_index, document_version, content, token_count, embedding, active, created_at
                ) values (?, ?, ?, ?, ?, ?, cast(? as vector), ?, ?)
                """,
                id, id, 0, 1, content, content.split("\\s+").length, unitVectorLiteral(), true, now);
    }

    private static String unitVectorLiteral() {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < EMBEDDING_DIMENSIONS; i++) {
            vector.append(i > 0 ? ",0.01" : "0.01");
        }
        return vector.append(']').toString();
    }

    @TestConfiguration
    static class StubEmbeddingConfig {

        @Bean
        @Primary
        EmbeddingClient embeddingClient(DataSource dataSource) {
            return text -> {
                ACTIVE_CONNECTIONS_DURING_EMBED.set(
                        ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections());
                return Collections.nCopies(EMBEDDING_DIMENSIONS, 0.01);
            };
        }
    }
}
