package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.dto.ScoredManualChunk;
import com.aicsassistant.analysis.eval.LiveEvalCorpus;
import com.aicsassistant.analysis.eval.LiveEvalReport;
import com.aicsassistant.analysis.infra.ManualChunkRetrievalRepository;
import com.aicsassistant.llm.EmbeddingClient;
import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.manual.domain.ManualVisibility;
import com.aicsassistant.order.application.OrderService;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ManualRetrievalService} 의 vector 임계값 두 개를 실제 임베딩 분포로 정하기 위한 보정 실행.
 * 평가셋 50건과 겹치지 않는 보정 질의 60건(관련 40 · 무관 20)만 쓴다. 근거는 ADR 0010.
 *
 * <p>게이트는 {@code vector ≥ T_vec OR (keyword ≥ 0.18 AND vector ≥ T_floor)} 다. 두 값 모두 같은 형태의
 * 규칙으로 고른다 — <b>보정셋의 무관 질의가 그 경로로 새어 나가는 최고 점수 + 0.02, 0.01 단위 올림</b>.
 * 거짓 양성 0 을 제약으로 두고 그 안에서 가장 낮은 값이다. 관련 질의 통과율은 결과로 기록할 뿐 규칙에
 * 넣지 않았다(넣으면 이 60건에 맞춘 값이 된다).
 *
 * <ul>
 *   <li>T_vec 규칙은 첫 보정 실행 전에 정했다</li>
 *   <li>T_floor 규칙은 첫 실행에서 T_vec 만으로는 관련 질의가 0/40 통과하는 것을 본 뒤 같은 형태로
 *       추가했다 — 보정셋을 본 뒤의 결정이지만 평가셋 50건은 보지 않았다</li>
 * </ul>
 *
 * <p>패키지가 {@code analysis.application} 인 이유: 운영과 같은 키워드 전처리
 * ({@code KoreanQueryPreprocessor}, 패키지 전용)를 그대로 써야 시뮬레이션이 운영 게이트와 같아진다.
 * LLM 은 부르지 않고 임베딩만 쓴다.
 */
@Tag("live-calibration")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@SpringBootTest(properties = "app.langfuse.enabled=false")
class VectorThresholdCalibrationTest extends PostgresVectorIntegrationTest {

    private static final Path QUERIES = Path.of("src/test/resources/eval/live/calibration-queries.jsonl");
    private static final Path OUT_DIR = Path.of("eval/calibration");
    private static final double MARGIN = 0.02;
    private static final double MIN_KEYWORD_SCORE = 0.18;
    /** 직전 운영값(변경 1). T_floor 는 이 값을 넘지 않게 고른다. */
    private static final double CURRENT_VEC = 0.57;
    private static final double CURRENT_FLOOR = 0.47;
    /** 고정 벡터 위에서 정했던 최초값. 비교용으로만 표에 둔다. */
    private static final double ORIGINAL_VEC = 0.75;
    private static final double ORIGINAL_FLOOR = 0.5;

    @Autowired ManualService manualService;
    @Autowired EmbeddingClient embeddingClient;
    @Autowired ManualChunkRetrievalRepository retrievalRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 질의 하나의 청크별 점수. keyword 는 임계 0.18 을 넘은 후보만 있다 — 운영과 같다. */
    private record Scored(String query, String expectedTitle, List<Chunk> chunks) {
    }

    private record Chunk(String title, double vector, double keyword) {
    }

    @Test
    void calibrate() throws Exception {
        LiveEvalCorpus.seed(manualService, LocalDate.now(OrderService.ZONE));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        List<Scored> scored = new ArrayList<>();
        for (String line : Files.readAllLines(QUERIES)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode q = mapper.readTree(line);
            String query = q.path("query").asText();
            String expected = q.path("expectedTitle").isNull() ? null : q.path("expectedTitle").asText();
            List<Double> embedding = embeddingClient.embed(query);
            scored.add(tx.execute(status -> score(query, expected, embedding)));
        }

        // 기대 문서가 고객 검색 대상이 아닌 질의(상담사용 문서 — ADR 0012)는 관련 집합에서 뺀다. 고객 경로에서
        // 못 찾는 것이 정답이라, 남겨 두면 분포의 바닥을 0 으로 끌어내린다.
        List<Scored> unreachable = scored.stream().filter(s -> s.expectedTitle() != null
                && s.chunks().stream().noneMatch(c -> c.title().equals(s.expectedTitle()))).toList();
        List<Scored> relevant = scored.stream()
                .filter(s -> s.expectedTitle() != null && !unreachable.contains(s)).toList();
        List<Scored> irrelevant = scored.stream().filter(s -> s.expectedTitle() == null).toList();

        double vec = pick(irrelevant.stream()
                .flatMap(s -> s.chunks().stream()).mapToDouble(Chunk::vector).max().orElse(0));
        double floor = Math.min(CURRENT_FLOOR, pick(irrelevant.stream()
                .flatMap(s -> s.chunks().stream()).filter(c -> c.keyword() >= MIN_KEYWORD_SCORE)
                .mapToDouble(Chunk::vector).max().orElse(0)));

        String stamp = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = OUT_DIR.resolve(stamp + "_" + LiveEvalReport.gitDescribe());
        Files.createDirectories(dir);
        try (var out = Files.newBufferedWriter(dir.resolve("scores.jsonl"))) {
            for (Scored s : scored) {
                out.write(mapper.writeValueAsString(s));
                out.newLine();
            }
        }
        Files.writeString(dir.resolve("summary.md"), summary(relevant, irrelevant, unreachable, vec, floor));
        System.out.printf("[calibration] T_vec=%.2f T_floor=%.2f → %s%n", vec, floor, dir.toAbsolutePath());

        assertThat(relevant.size() + unreachable.size()).isEqualTo(40);
        assertThat(irrelevant).hasSize(20);
    }

    private Scored score(String query, String expected, List<Double> embedding) {
        Map<Long, Double> keyword = new HashMap<>();
        String cleaned = KoreanQueryPreprocessor.forKeywordSearch(query);
        if (!cleaned.isBlank()) {
            for (ScoredManualChunk c : retrievalRepository.findKeywordCandidates(cleaned, MIN_KEYWORD_SCORE, 20, ManualVisibility.CUSTOMER)) {
                keyword.put(c.chunk().id(), c.score());
            }
        }
        List<Chunk> chunks = retrievalRepository.findVectorCandidates(embedding, 1000, ManualVisibility.CUSTOMER).stream()
                .map(c -> new Chunk(c.chunk().manualDocumentTitle(), round(c.score()),
                        round(keyword.getOrDefault(c.chunk().id(), 0.0))))
                .toList();
        return new Scored(query, expected, chunks);
    }

    private static double pick(double maxLeak) {
        return Math.ceil((maxLeak + MARGIN) * 100) / 100.0;
    }

    private static boolean passes(Chunk c, double vec, double floor) {
        return c.vector() >= vec || (c.keyword() >= MIN_KEYWORD_SCORE && c.vector() >= floor);
    }

    /** 관련 질의: 기대 문서의 청크가 하나라도 게이트를 통과하는가. */
    private static long relevantHits(List<Scored> rel, double vec, double floor) {
        return rel.stream().filter(s -> s.chunks().stream()
                .anyMatch(c -> c.title().equals(s.expectedTitle()) && passes(c, vec, floor))).count();
    }

    /** 무관 질의: 어떤 청크든 게이트를 통과하는가 (= 결과가 비지 않는가). */
    private static long irrelevantLeaks(List<Scored> irr, double vec, double floor) {
        return irr.stream().filter(s -> s.chunks().stream().anyMatch(c -> passes(c, vec, floor))).count();
    }

    private String summary(List<Scored> rel, List<Scored> irr, List<Scored> unreachable, double vec, double floor) {
        List<Double> relBest = rel.stream().map(s -> s.chunks().stream()
                .filter(c -> c.title().equals(s.expectedTitle())).mapToDouble(Chunk::vector).max().orElse(0))
                .sorted().toList();
        List<Double> irrTop = irr.stream().map(s -> s.chunks().get(0).vector()).sorted().toList();
        long relTop1 = rel.stream().filter(s -> s.chunks().get(0).title().equals(s.expectedTitle())).count();
        long relKeyword = rel.stream().filter(s -> s.chunks().stream()
                .anyMatch(c -> c.title().equals(s.expectedTitle()) && c.keyword() >= MIN_KEYWORD_SCORE)).count();
        long irrKeyword = irr.stream().filter(s -> s.chunks().stream()
                .anyMatch(c -> c.keyword() >= MIN_KEYWORD_SCORE)).count();

        StringBuilder sb = new StringBuilder("# vector 임계값 보정\n\n");
        sb.append("보정 질의 60건(관련 40 · 무관 20), 평가셋 50건과 겹치지 않음. 코퍼스는 평가와 같다(LiveEvalCorpus).\n\n")
                .append("관련 질의 중 기대 문서가 고객 검색 대상이 아니라 제외: ").append(unreachable.size()).append("건")
                .append(unreachable.isEmpty() ? "" : " — " + unreachable.stream().map(Scored::query).toList())
                .append("\n\n")
                .append("## vector 유사도 분포\n\n관련은 기대 문서 청크 중 최고, 무관은 전체 top-1.\n\n")
                .append("| 분포 | n | min | p10 | p25 | p50 | p75 | p90 | max |\n|---|---|---|---|---|---|---|---|---|\n")
                .append(distRow("관련", relBest)).append(distRow("무관", irrTop))
                .append("\n- 기대 문서가 vector top-1: ").append(relTop1).append('/').append(rel.size())
                .append("\n- 기대 문서 청크가 keyword ≥ 0.18: ").append(relKeyword).append('/').append(rel.size())
                .append("\n- 무관 질의에서 keyword ≥ 0.18 청크가 있음: ").append(irrKeyword).append('/').append(irr.size())
                .append("\n\n## 게이트 시뮬레이션\n\n`vector ≥ T_vec OR (keyword ≥ 0.18 AND vector ≥ T_floor)`\n\n")
                .append("| T_vec | T_floor | 관련 통과 | 무관 통과(거짓 양성) |\n|---|---|---|---|\n");
        sb.append(row(ORIGINAL_VEC, ORIGINAL_FLOOR, rel, irr, " (최초, 고정 벡터 기준)"));
        sb.append(row(CURRENT_VEC, CURRENT_FLOOR, rel, irr, " (직전)"));
        for (double f = 0.50; f >= 0.199; f -= 0.05) {
            sb.append(row(vec, Math.round(f * 100) / 100.0, rel, irr, ""));
        }
        sb.append(row(vec, floor, rel, irr, " **← 선택**"));
        sb.append("\n**선택 규칙**: 각 경로로 새어 나간 무관 질의의 최고 vector 점수 + ").append(MARGIN)
                .append(", 0.01 단위 올림 (T_floor 는 직전값을 넘지 않는다)\n");
        return sb.toString();
    }

    private static String row(double vec, double floor, List<Scored> rel, List<Scored> irr, String note) {
        return "| %.2f | %.2f | %d/%d | %d/%d |%s%n".formatted(vec, floor,
                relevantHits(rel, vec, floor), rel.size(), irrelevantLeaks(irr, vec, floor), irr.size(), note);
    }

    private static String distRow(String name, List<Double> sorted) {
        return "| %s | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |%n".formatted(name, sorted.size(),
                sorted.get(0), pct(sorted, .10), pct(sorted, .25), pct(sorted, .50), pct(sorted, .75),
                pct(sorted, .90), sorted.get(sorted.size() - 1));
    }

    private static double pct(List<Double> sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
