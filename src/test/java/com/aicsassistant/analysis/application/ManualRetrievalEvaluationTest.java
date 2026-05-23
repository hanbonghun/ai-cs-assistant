package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.infra.llm.EmbeddingClient;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
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
 * RAG 검색 품질 평가 (CSV 골든셋 기반).
 *
 * <p>FakeEmbedding은 케이스의 difficulty에 따라 의도적으로 강/약 신호를 만든다.
 * 현재 baseline은 hard 케이스가 threshold(0.75)에 걸려 잡히지 않는 상태이며,
 * 검색 로직(예: keyword hybrid)을 개선하면 hard 케이스가 통과되며 메트릭이 올라가야 한다.
 */
@SpringBootTest(properties = "app.ai.api-key=test-key")
@Import(ManualRetrievalEvaluationTest.FakeEmbeddingConfig.class)
class ManualRetrievalEvaluationTest extends PostgresVectorIntegrationTest {

    private static final int EMBEDDING_DIMENSIONS = 1536;
    private static final int DISTRACTOR_AXIS = 6;

    private static final Map<String, Integer> TITLE_TO_AXIS = Map.of(
            "환불 정책", 0,
            "배송 정책", 1,
            "교환 정책", 2,
            "반품 정책", 3,
            "회원 정책", 4,
            "결제 정책", 5
    );

    private static List<RagEvaluationCase> goldenCases;

    @Autowired
    ManualRetrievalService manualRetrievalService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void loadGoldenSet() {
        goldenCases = RagGoldenSetLoader.load(Path.of("src/test/resources/eval/rag-golden-set.csv"));
    }

    @BeforeEach
    void seedManualChunks() {
        seedManualDocument(1L, "환불 정책", "REFUND",
                "환불은 수령 후 7일 이내 신청할 수 있습니다. 단순 변심도 포장 미개봉 시 가능합니다.", 0);
        seedManualDocument(2L, "배송 정책", "DELIVERY",
                "배송 지연은 영업일 기준으로 확인하며 일정 기간 초과 시 보상 기준을 안내합니다.", 1);
        seedManualDocument(3L, "교환 정책", "EXCHANGE",
                "교환은 상품 수령 후 정해진 기간 내 접수할 수 있습니다.", 2);
        seedManualDocument(4L, "반품 정책", "RETURN",
                "반품 신청 시 원포장을 유지해주시고 회수 일정은 별도 안내드립니다.", 3);
        seedManualDocument(5L, "회원 정책", "MEMBERSHIP",
                "회원 등급은 최근 구매 금액 기준으로 산정합니다.", 4);
        seedManualDocument(6L, "결제 정책", "PAYMENT",
                "결제 수단은 신용카드, 계좌이체, 간편결제를 지원하며 현금영수증 발급이 가능합니다.", 5);
    }

    @Test
    @DisplayName("positive 케이스의 Recall@3는 baseline 기준선을 넘어야 한다")
    void positiveRecallMeetsBaseline() {
        List<Integer> ranks = positiveCases().stream()
                .map(this::rankExpectedManualTitle)
                .toList();

        double recallAt3 = RagRetrievalMetrics.recallAt(ranks, 3);
        double mrr = RagRetrievalMetrics.meanReciprocalRank(ranks);

        // baseline: easy/medium 통과(58) + hard 미통과(14) = 58/72 ≈ 0.806
        assertThat(recallAt3).isGreaterThanOrEqualTo(0.75);
        assertThat(mrr).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    @DisplayName("easy+medium 난이도는 모두 1순위로 잡혀야 한다")
    void easyAndMediumAreAlwaysRetrievedAtRankOne() {
        List<Integer> ranks = positiveCases().stream()
                .filter(c -> c.difficulty() != RagEvaluationCase.Difficulty.HARD)
                .map(this::rankExpectedManualTitle)
                .toList();

        assertThat(ranks).isNotEmpty();
        assertThat(RagRetrievalMetrics.recallAt(ranks, 1)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("negative 케이스는 threshold에 걸려 결과가 비어야 한다")
    void negativeCasesProduceNoMatches() {
        List<RagEvaluationCase> negatives = goldenCases.stream()
                .filter(RagEvaluationCase::negative)
                .toList();

        assertThat(negatives).isNotEmpty();

        List<Integer> retrievedCounts = negatives.stream()
                .map(c -> manualRetrievalService.retrieve(c.query()).size())
                .toList();

        assertThat(RagRetrievalMetrics.noMatchAccuracy(retrievedCounts)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("hard 케이스는 현재 baseline에서 잡히지 않는다 (개선 여지 가시화)")
    void hardCasesAreCurrentlyFilteredOut() {
        List<Integer> hardRanks = goldenCases.stream()
                .filter(c -> !c.negative() && c.difficulty() == RagEvaluationCase.Difficulty.HARD)
                .map(this::rankExpectedManualTitle)
                .toList();

        assertThat(hardRanks).isNotEmpty();
        // 검색 품질 개선 시 이 테스트가 깨져야 하며 그때 새 baseline으로 갱신한다.
        assertThat(RagRetrievalMetrics.recallAt(hardRanks, 3)).isLessThanOrEqualTo(0.25);
    }

    @Test
    @DisplayName("검색 결과는 similarity score를 노출한다")
    void retrievedChunksExposeSimilarityScore() {
        List<RetrievedManualChunkDto> retrieved = manualRetrievalService.retrieve("환불 기간 알려줘");

        assertThat(retrieved).isNotEmpty();
        assertThat(retrieved.get(0).manualDocumentTitle()).isEqualTo("환불 정책");
        assertThat(retrieved.get(0).similarityScore()).isNotNull();
        assertThat(retrieved.get(0).similarityScore()).isGreaterThanOrEqualTo(0.75);
    }

    private List<RagEvaluationCase> positiveCases() {
        return goldenCases.stream().filter(c -> !c.negative()).toList();
    }

    private int rankExpectedManualTitle(RagEvaluationCase evaluationCase) {
        List<RetrievedManualChunkDto> retrieved = manualRetrievalService.retrieve(evaluationCase.query());

        for (int i = 0; i < retrieved.size(); i++) {
            if (retrieved.get(i).manualDocumentTitle().equals(evaluationCase.expectedManualTitle())) {
                return i + 1;
            }
        }
        return RagRetrievalMetrics.NOT_FOUND_RANK;
    }

    private void seedManualDocument(Long id, String title, String category, String content, int embeddingAxis) {
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
                id,
                id,
                0,
                1,
                content,
                content.split("\\s+").length,
                singleAxisVectorLiteral(embeddingAxis),
                true,
                now
        );
    }

    private static String singleAxisVectorLiteral(int activeAxis) {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < EMBEDDING_DIMENSIONS; i++) {
            if (i > 0) {
                vector.append(',');
            }
            vector.append(i == activeAxis ? "1.0" : "0.0");
        }
        return vector.append(']').toString();
    }

    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            Map<String, List<Double>> embeddingByQuery = buildEmbeddingMap();
            return text -> embeddingByQuery.getOrDefault(text, uniformNoiseVector());
        }

        private static Map<String, List<Double>> buildEmbeddingMap() {
            List<RagEvaluationCase> cases = RagGoldenSetLoader.load(
                    Path.of("src/test/resources/eval/rag-golden-set.csv"));

            Map<String, List<Double>> map = new HashMap<>();
            for (RagEvaluationCase c : cases) {
                map.put(c.query(), vectorFor(c));
            }
            return map;
        }

        private static List<Double> vectorFor(RagEvaluationCase c) {
            if (c.negative()) {
                return uniformNoiseVector();
            }
            int axis = TITLE_TO_AXIS.get(c.expectedManualTitle());
            return switch (c.difficulty()) {
                case EASY -> sparseVector(Map.of(axis, 1.0));
                case MEDIUM -> sparseVector(Map.of(axis, 0.9, DISTRACTOR_AXIS, 0.4));
                case HARD -> sparseVector(Map.of(axis, 0.6, DISTRACTOR_AXIS, 0.7));
            };
        }

        private static List<Double> sparseVector(Map<Integer, Double> axisWeights) {
            Double[] vector = new Double[EMBEDDING_DIMENSIONS];
            for (int i = 0; i < EMBEDDING_DIMENSIONS; i++) {
                vector[i] = axisWeights.getOrDefault(i, 0.0);
            }
            return List.of(vector);
        }

        private static List<Double> uniformNoiseVector() {
            Double[] vector = new Double[EMBEDDING_DIMENSIONS];
            for (int i = 0; i < EMBEDDING_DIMENSIONS; i++) {
                vector[i] = TITLE_TO_AXIS.containsValue(i) ? 0.05 : 0.0;
            }
            return List.of(vector);
        }
    }
}
