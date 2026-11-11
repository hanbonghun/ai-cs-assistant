package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.llm.EmbeddingClient;
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
 * RAG 검색 <b>로직</b> 회귀 테스트 (CSV 골든셋 기반).
 *
 * <p>임베딩을 고정하므로 이 테스트의 수치는 실제 검색 품질이 아니다 — fusion·게이트·전처리를
 * 바꿨을 때 결과가 어떻게 움직이는지를 결정론적으로 고정하는 용도다. 실제 임베딩·모델 기준 품질은
 * {@code LiveRagEvaluationTest} 가 잰다 (ADR 0010).
 *
 * <p>FakeEmbedding은 케이스의 difficulty에 따라 의도적으로 강/약 신호를 만든다.
 * hard 케이스는 vector 단독 임계(0.57) 아래, keyword 경로 하한(0.47) 위(cos ≈ 0.54)에 놓아 keyword 가 회복을 가르게 한다.
 * 임계값을 바꾸면 이 벡터도 같은 구간에 다시 놓아야 테스트의 뜻이 유지된다. 현재 baseline은 hard 케이스 대부분이 잡히지 않는 상태이며,
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

        // hybrid 도입 후 baseline: easy/medium 통과(58) + hard 일부(2) = 60/72 ≈ 0.833
        assertThat(recallAt3).isGreaterThanOrEqualTo(0.80);
        assertThat(mrr).isGreaterThanOrEqualTo(0.80);
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
    @DisplayName("hard 케이스는 hybrid 키워드 검색으로 일부 회복된다")
    void hardCasesArePartiallyRescuedByHybrid() {
        List<Integer> hardRanks = goldenCases.stream()
                .filter(c -> !c.negative() && c.difficulty() == RagEvaluationCase.Difficulty.HARD)
                .map(this::rankExpectedManualTitle)
                .toList();

        assertThat(hardRanks).isNotEmpty();
        // hybrid 도입 후 baseline: 14개 중 2개 회복 (0.143). 추가 개선으로 더 올라가야 한다.
        assertThat(RagRetrievalMetrics.recallAt(hardRanks, 3)).isBetween(0.10, 0.50);
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

    @Test
    @DisplayName("[리포트] 전체 메트릭 출력 (재측정용)")
    void reportAllMetricsDiagnostic() {
        List<Integer> positiveRanks = positiveCases().stream().map(this::rankExpectedManualTitle).toList();
        List<Integer> hardRanks = goldenCases.stream()
                .filter(c -> !c.negative() && c.difficulty() == RagEvaluationCase.Difficulty.HARD)
                .map(this::rankExpectedManualTitle).toList();
        List<Integer> negativeCounts = goldenCases.stream()
                .filter(RagEvaluationCase::negative)
                .map(c -> manualRetrievalService.retrieve(c.query()).size())
                .toList();

        System.out.println("\n=== RAG Evaluation (post P1/P2 fix) ===");
        System.out.printf("Positive Recall@3 : %.4f%n", RagRetrievalMetrics.recallAt(positiveRanks, 3));
        System.out.printf("Positive MRR      : %.4f%n", RagRetrievalMetrics.meanReciprocalRank(positiveRanks));
        System.out.printf("Hard Recall@3     : %.4f%n", RagRetrievalMetrics.recallAt(hardRanks, 3));
        System.out.printf("NoMatchAccuracy   : %.4f%n", RagRetrievalMetrics.noMatchAccuracy(negativeCounts));
        System.out.println("========================================\n");
    }

    @Test
    @DisplayName("[P1 회귀] keyword-strong 후보가 vector top-K 밖에 있어도 회복된다")
    void keywordOnlyCandidateOutsideVectorTopKIsRescued() {
        // distractor 25개 추가:
        // - vector cos ≈ 0.56 (RETURN doc의 0.54 보다 약간 위) → vector top-20을 점령해 RETURN을 21위 밖으로 밀어냄
        // - cos 0.56 < 0.57 (strong threshold) 라 vector path 만으론 통과 못 함
        // - content에는 "회수" 키워드 없음 → keyword 경로로도 통과 못 함 → gate에서 떨어짐
        // - 결과적으로 distractor는 최종 결과에 안 남고, P1이 정상이라면 RETURN만 회복되어 rank 1로 잡힘
        // P1 수정 전 (버그): vector candidates 21위 밖인 RETURN이 vectorScore=0.0 이라 gate 두 번째 조건도 실패 → empty 결과
        for (int i = 0; i < 25; i++) {
            int noiseAxis = 7 + i;  // 각 distractor는 서로 다른 noise axis를 사용해 orthogonal
            seedDistractorChunk(100L + i, "관련 없는 잡담 콘텐츠 " + i, noiseAxis);
        }

        List<RetrievedManualChunkDto> retrieved =
                manualRetrievalService.retrieve("택배 도로 회수해갈 수 있나요");

        assertThat(retrieved).isNotEmpty();
        assertThat(retrieved.get(0).manualDocumentTitle()).isEqualTo("반품 정책");
    }

    private void seedDistractorChunk(long id, String content, int noiseAxis) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                insert into manual_document (id, title, category, content, version, active, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, "distractor-" + id, "GENERAL", content, 1, true, now, now);

        // hard query 방향(축 3, 축 6)에 약하게 정렬 + orthogonal noise → cos ≈ 0.56 (RETURN 0.54 보다 살짝 위, 0.57 아래)
        Map<Integer, Double> axes = Map.of(
                TITLE_TO_AXIS.get("반품 정책"), 0.6,
                DISTRACTOR_AXIS, 0.7,
                noiseAxis, 1.35
        );
        jdbcTemplate.update("""
                insert into manual_chunk (
                    id, manual_document_id, chunk_index, document_version, content, token_count, embedding, active, created_at
                ) values (?, ?, ?, ?, ?, ?, cast(? as vector), ?, ?)
                """,
                id, id, 0, 1, content, content.split("\\s+").length,
                sparseVectorLiteral(axes),
                true, now
        );
    }

    private static String sparseVectorLiteral(Map<Integer, Double> axes) {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < EMBEDDING_DIMENSIONS; i++) {
            if (i > 0) {
                vector.append(',');
            }
            vector.append(axes.getOrDefault(i, 0.0));
        }
        return vector.append(']').toString();
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
                case HARD -> sparseVector(Map.of(axis, 0.45, DISTRACTOR_AXIS, 0.7));
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
