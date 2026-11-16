package com.aicsassistant.analysis.eval;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 저장된 실행 원자료를 고정 judge({@link LiveEvalJudge})로 다시 채점한다. 에이전트·DB 는 다시 돌리지 않는다.
 *
 * <p>이전 실행은 에이전트와 같은 모델이 채점해서, 모델을 바꾼 실행과 judge 지표를 나란히 둘 수 없었다.
 * 답변과 도구 관측 원문이 {@code cases.jsonl} 에 남아 있으므로 judge 만 바꿔 다시 매긴다. 결과는 각 실행 아래
 * {@code judge-<모델>/} 에, 실행 전체 비교표는 {@code eval/runs/comparison-judge-<모델>.md} 에 쓴다.
 * 이미 채점한 실행은 건너뛴다 — 다시 매기려면 그 디렉터리를 지운다.
 *
 * <p>현재 채점기 버전으로 매긴 원자료({@code rescore-v<버전>/} 가 있으면 그쪽)만, 그리고 도구 관측 원문이 남은
 * 실행만 대상이다(기준선·변경 1 은 없다). judge 외 지표는
 * 그대로 옮긴다. 라벨은 {@code -Plabels=20261112-062310=기준선,...} 로 붙인다.
 */
@Tag("live-rejudge")
class LiveEvalRejudgeTest {

    private static final Path RUNS_DIR = Path.of("eval/runs");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejudgeAllRuns() throws Exception {
        LiveEvalJudge judge = LiveEvalJudge.fromSystemProperties(mapper);
        String outDirName = "judge-" + judge.model();
        Map<String, String> labels = parseLabels(System.getProperty("liveEval.labels", ""));

        List<Path> runs;
        try (Stream<Path> dirs = Files.list(RUNS_DIR)) {
            runs = dirs.filter(Files::isDirectory).sorted().toList();
        }

        List<JsonNode> comparable = new ArrayList<>();
        Optional<JsonNode> previous = Optional.empty();
        for (Path run : runs) {
            Optional<Path> source = currentVersionCases(run);
            if (source.isEmpty()) {
                System.out.println("[rejudge] 현재 채점기 버전의 원자료가 없어 건너뜀: " + run.getFileName());
                continue;
            }
            JsonNode sourceMeta = mapper.readTree(source.get().resolveSibling("metrics.json").toFile()).path("meta");
            String label = labels.entrySet().stream()
                    .filter(e -> run.getFileName().toString().startsWith(e.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(sourceMeta.path("label").asText(""));

            if (!hasObservations(source.get())) {
                // 기준선·변경 1 은 도구 관측 원문을 남기지 않았다. 근거 없이 채점하면 전부 "근거 없음" 이 되어 비교를 망친다
                System.out.println("[rejudge] 도구 관측 원문이 없어 건너뜀: " + run.getFileName());
                continue;
            }
            Path outDir = run.resolve(outDirName);
            if (!Files.exists(outDir.resolve("metrics.json"))) {
                LocalDate today = LocalDate.parse(sourceMeta.path("today").asText());
                List<JsonNode> rejudged = new ArrayList<>();
                for (String line : Files.readAllLines(source.get())) {
                    if (!line.isBlank()) {
                        rejudged.add(rejudge((ObjectNode) mapper.readTree(line), judge, today));
                    }
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = mapper.convertValue(sourceMeta, LinkedHashMap.class);
                meta.put("judge", judge.model());
                meta.put("judgeOriginal", sourceMeta.path("judge").asText(""));
                meta.put("rejudgedFrom", run.relativize(source.get()).toString());
                meta.put("label", label);
                LiveEvalReport.writeTo(outDir, meta, rejudged, previous, mapper);
                System.out.println("[rejudge] " + run.getFileName() + " → " + outDirName);
            }

            ObjectNode metrics = (ObjectNode) mapper.readTree(outDir.resolve("metrics.json").toFile());
            metrics.put("dir", run.getFileName().toString());
            metrics.put("label", label);
            comparable.add(metrics);
            previous = Optional.of(metrics);
        }

        Path out = RUNS_DIR.resolve("comparison-" + outDirName + ".md");
        Files.writeString(out, LiveEvalReport.comparison(comparable, LiveEvalReport.ratioLabels(),
                "실측 평가 실행 비교 (채점기 v" + LiveEvalScorer.VERSION + ", judge " + judge.model() + ")",
                "모든 실행의 근거 일치(judge)를 같은 모델로 다시 매겼다. judge 외 지표는 원 실행 값 그대로다."));
        System.out.println("[rejudge] 비교표 → " + out.toAbsolutePath());
        assertThat(comparable).isNotEmpty();
    }

    /** 원 실행과 같은 조건으로만 채점한다 — 실행 오류이거나 답이 비었으면 judge 를 두지 않는다. */
    private JsonNode rejudge(ObjectNode row, LiveEvalJudge judge, LocalDate today) {
        row.remove("judge");
        JsonNode agent = row.path("agent");
        String answer = agent.path("answer").asText("");
        if ("ERROR".equals(agent.path("outcome").asText()) || answer.isBlank()) {
            return row;
        }
        String evidence = LiveEvalJudge.evidence(strings(agent.path("tools")), strings(agent.path("observations")));
        row.set("judge", mapper.valueToTree(judge.judge(row.path("query").asText(), answer, evidence, today)));
        return row;
    }

    /** 도구를 쓴 케이스가 모두 관측 원문을 갖고 있어야 judge 가 원 실행과 같은 근거를 본다. */
    private boolean hasObservations(Path cases) throws Exception {
        for (String line : Files.readAllLines(cases)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode agent = mapper.readTree(line).path("agent");
            if (agent.path("tools").size() > 0 && agent.path("observations").size() != agent.path("tools").size()) {
                return false;
            }
        }
        return true;
    }

    private Optional<Path> currentVersionCases(Path run) throws Exception {
        for (Path dir : List.of(run.resolve("rescore-v" + LiveEvalScorer.VERSION), run)) {
            Path metrics = dir.resolve("metrics.json");
            if (Files.exists(metrics) && Files.exists(dir.resolve("cases.jsonl"))
                    && LiveEvalScorer.VERSION.equals(mapper.readTree(metrics.toFile()).path("meta").path("scorerVersion").asText())) {
                return Optional.of(dir.resolve("cases.jsonl"));
            }
        }
        return Optional.empty();
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
