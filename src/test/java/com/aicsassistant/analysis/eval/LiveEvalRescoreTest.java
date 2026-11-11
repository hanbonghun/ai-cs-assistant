package com.aicsassistant.analysis.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.eval.LiveEvalScorer.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 저장된 실행 원자료({@code cases.jsonl})를 현재 채점기로 다시 매긴다. 모델·DB·네트워크를 쓰지 않는다.
 *
 * <p>채점 규칙을 고치면 이전 실행의 숫자는 옛 잣대로 매긴 것이라 새 실행과 나란히 둘 수 없다. 답변과 경로는
 * 원자료에 남아 있으므로, 모델을 다시 부르지 않고 채점만 다시 해서 같은 잣대로 맞춘다. 결과는 각 실행 아래
 * {@code rescore-v<버전>/} 에, 실행 전체 비교표는 {@code eval/runs/comparison-scorer-v<버전>.md} 에 쓴다.
 *
 * <p>검색 지표(순위·stale 노출)와 judge 는 원자료 값을 그대로 둔다 — 채점기 버전과 무관한 측정값이다.
 *
 * <p>라벨: {@code ./gradlew liveRescore -Plabels=20261112-062310=기준선,20261112-063733=변경1}
 * (실행 디렉터리 이름의 앞부분으로 맞춘다). 지정하지 않으면 원 실행의 라벨을 쓴다.
 */
@Tag("live-rescore")
class LiveEvalRescoreTest {

    private static final Path EVAL_SET = Path.of("src/test/resources/eval/live/live-eval-set.jsonl");
    private static final Path RUNS_DIR = Path.of("eval/runs");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rescoreAllRuns() throws Exception {
        Map<String, String> labels = parseLabels(System.getProperty("liveEval.labels", ""));
        List<Path> runs;
        try (Stream<Path> dirs = Files.list(RUNS_DIR)) {
            runs = dirs.filter(p -> Files.exists(p.resolve("cases.jsonl"))).sorted().toList();
        }

        List<JsonNode> comparable = new ArrayList<>();
        Optional<JsonNode> previous = Optional.empty();
        for (Path run : runs) {
            JsonNode original = mapper.readTree(run.resolve("metrics.json").toFile());
            JsonNode meta = original.path("meta");
            String label = labels.entrySet().stream()
                    .filter(e -> run.getFileName().toString().startsWith(e.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(meta.path("label").asText(""));

            if (!LiveEvalScorer.VERSION.equals(meta.path("scorerVersion").asText())) {
                if (!LiveEvalReport.sha256File(EVAL_SET).equals(meta.path("evalSetSha256").asText())) {
                    System.out.println("[rescore] 평가셋이 달라 건너뜀: " + run.getFileName());
                    continue;
                }
                Map<String, LiveEvalCase> byId = LiveEvalCase.load(EVAL_SET, LocalDate.parse(meta.path("today").asText()))
                        .stream().collect(Collectors.toMap(LiveEvalCase::id, Function.identity()));
                List<JsonNode> rescored = new ArrayList<>();
                for (String line : Files.readAllLines(run.resolve("cases.jsonl"))) {
                    if (!line.isBlank()) {
                        rescored.add(rescore((ObjectNode) mapper.readTree(line), byId, meta.path("today").asText()));
                    }
                }
                Map<String, Object> newMeta = mapper.convertValue(meta, LinkedHashMap.class);
                newMeta.put("scorerVersion", LiveEvalScorer.VERSION);
                newMeta.put("rescoredFrom", "원 실행의 cases.jsonl (채점기 v" + meta.path("scorerVersion").asText("1") + ")");
                newMeta.put("label", label);
                LiveEvalReport.writeTo(run.resolve("rescore-v" + LiveEvalScorer.VERSION), newMeta, rescored, previous, mapper);
                System.out.println("[rescore] " + run.getFileName() + " → rescore-v" + LiveEvalScorer.VERSION);
            }

            Optional<JsonNode> current = LiveEvalReport.currentVersionMetrics(run, mapper);
            if (current.isPresent()) {
                ObjectNode node = (ObjectNode) current.get();
                node.put("label", label.isBlank() ? node.path("meta").path("label").asText("") : label);
                comparable.add(node);
                previous = current;
            }
        }

        Path out = RUNS_DIR.resolve("comparison-scorer-v" + LiveEvalScorer.VERSION + ".md");
        Files.writeString(out, LiveEvalReport.comparison(comparable, LiveEvalReport.ratioLabels()));
        System.out.println("[rescore] 비교표 → " + out.toAbsolutePath());
        assertThat(comparable).isNotEmpty();
    }

    private JsonNode rescore(ObjectNode row, Map<String, LiveEvalCase> byId, String today) {
        LiveEvalCase c = byId.get(row.path("id").asText());
        JsonNode agent = row.path("agent");
        Outcome outcome = Outcome.valueOf(agent.path("outcome").asText());
        Map<String, Object> checks = LiveEvalScorer.checks(c, agent.path("answer").asText(), outcome,
                strings(agent.path("tools")), strings(agent.path("collectedTitles")), today);
        row.set("checks", mapper.valueToTree(checks));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("abstain", c.expectAbstain());
        expected.put("mustCallTools", c.mustCallTools());
        expected.put("routing", c.expectedRouting().name());
        expected.put("mustInclude", c.mustInclude());
        expected.put("forbidden", c.forbidden());
        expected.put("evidence", c.expectedEvidence());
        row.set("expected", mapper.valueToTree(expected));
        return row;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, String> parseLabels(String raw) {
        Map<String, String> labels = new LinkedHashMap<>();
        Arrays.stream(raw.split(",")).map(String::trim).filter(s -> s.contains("=")).forEach(s -> {
            String[] kv = s.split("=", 2);
            labels.put(kv[0].trim(), kv[1].trim());
        });
        return labels;
    }
}
