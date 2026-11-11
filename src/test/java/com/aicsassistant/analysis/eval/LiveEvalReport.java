package com.aicsassistant.analysis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 실측 평가 한 번의 결과를 {@code eval/runs/<시각>_<커밋>/} 에 남기고, 직전 실행과 나란히 놓는다.
 *
 * <ul>
 *   <li>{@code cases.jsonl} — 케이스별 원자료(검색 결과·답변·토큰·지연). 눈으로 검수할 때 본다</li>
 *   <li>{@code metrics.json} — 아래 지표. 다음 실행이 비교 기준으로 읽는다</li>
 *   <li>{@code summary.md} — 사람이 읽는 표</li>
 * </ul>
 *
 * <p>지표는 단계별로 따로 두고 하나로 합치지 않는다. 합치는 순간 어느 단계가 틀렸는지 사라진다.
 */
public final class LiveEvalReport {

    private static final List<String> GROUPS = List.of(
            "STALE", "PARAPHRASE", "COMPOSITE", "INJECTION", "OUT_OF_SCOPE", "ALL");

    /** 지표 키 → 표에 쓸 이름. 순서가 표의 행 순서다. */
    private static final Map<String, String> RATIO_LABELS = labels(
            "r.recall@1", "검색 Recall@1 (근거 문구 단위)",
            "r.recall@3", "검색 Recall@3",
            "r.recall@5", "검색 Recall@5",
            "r.noMatch", "검색 빈 결과율 (근거 없는 케이스, 참고)",
            "r.staleLeak", "검색 stale 노출 ↓",
            "a.evidenceCoverage", "에이전트가 모은 근거 적중",
            "a.toolSelection", "필수 도구 호출",
            "a.staleLeak", "에이전트 stale 노출 ↓",
            "g.mustInclude", "필수 사실 포함",
            "g.forbiddenClean", "금지 문구 없음",
            "g.routing", "라우팅 일치",
            "g.abstain", "근거 없을 때 거절·이관",
            "g.grounded", "근거 일치 (judge)",
            "g.deflection", "고객센터 떠넘기기 ↓",
            "g.error", "실행 오류 ↓");

    private static final Map<String, String> COST_LABELS = labels(
            "c.latencyP50Ms", "에이전트 지연 p50 (ms)",
            "c.latencyP95Ms", "에이전트 지연 p95 (ms)",
            "c.retrievalAvgMs", "검색 단독 지연 평균 (ms)",
            "c.llmCallsAvg", "건당 LLM 호출",
            "c.promptTokensAvg", "건당 입력 토큰",
            "c.completionTokensAvg", "건당 출력 토큰",
            "c.cachedTokensAvg", "건당 캐시 적중 입력 토큰",
            "c.judgeTokensTotal", "judge 토큰 합계");

    private LiveEvalReport() {
    }

    static Path write(Path runsDir, Map<String, Object> meta, List<Map<String, Object>> rows, ObjectMapper mapper)
            throws IOException {
        String stamp = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = runsDir.resolve(stamp + "_" + meta.get("git"));
        Files.createDirectories(dir);

        List<JsonNode> cases = rows.stream().map(r -> (JsonNode) mapper.valueToTree(r)).toList();
        Map<String, Map<String, Object>> metrics = metrics(cases);
        Optional<JsonNode> previous = previousMetrics(runsDir, dir, mapper);

        try (var out = Files.newBufferedWriter(dir.resolve("cases.jsonl"))) {
            for (JsonNode c : cases) {
                out.write(mapper.writeValueAsString(c));
                out.newLine();
            }
        }
        Map<String, Object> metricsFile = new LinkedHashMap<>();
        metricsFile.put("meta", meta);
        metricsFile.put("metrics", metrics);
        mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("metrics.json").toFile(), metricsFile);
        Files.writeString(dir.resolve("summary.md"), summary(meta, metrics, previous, cases));
        return dir;
    }

    // ---------- 지표 ----------

    static Map<String, Map<String, Object>> metrics(List<JsonNode> cases) {
        Map<String, Map<String, Object>> byGroup = new LinkedHashMap<>();
        for (String group : GROUPS) {
            List<JsonNode> in = cases.stream()
                    .filter(c -> group.equals("ALL") || group.equals(c.path("type").asText()))
                    .toList();
            byGroup.put(group, groupMetrics(in));
        }
        return byGroup;
    }

    private static Map<String, Object> groupMetrics(List<JsonNode> cs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseCount", cs.size());
        List<Integer> ranks =cs.stream().flatMap(c -> ints(c.path("retrieval").path("evidenceRanks"))).toList();
        for (int k : new int[]{1, 3, 5}) {
            m.put("r.recall@" + k, ratio(ranks.stream().filter(r -> r > 0 && r <= k).count(), ranks.size()));
        }
        List<JsonNode> noEvidence = cs.stream().filter(c -> c.path("expected").path("evidence").isEmpty()).toList();
        m.put("r.noMatch", ratio(count(noEvidence, c -> c.path("retrieval").path("resultCount").asInt() == 0), noEvidence.size()));
        List<JsonNode> withMarkers = cs.stream().filter(c -> c.path("retrieval").has("staleLeaks")
                && isStaleCase(c)).toList();
        m.put("r.staleLeak", ratio(count(withMarkers, c -> !c.path("retrieval").path("staleLeaks").isEmpty()), withMarkers.size()));

        List<Boolean> covered = cs.stream().flatMap(c -> bools(c.path("agent").path("evidenceCovered"))).toList();
        m.put("a.evidenceCoverage", ratio(covered.stream().filter(b -> b).count(), covered.size()));
        m.put("a.toolSelection", nullableRatio(cs, "toolsOk", true));
        m.put("a.staleLeak", ratio(count(withMarkers, c -> !c.path("agent").path("staleLeaks").isEmpty()), withMarkers.size()));

        List<JsonNode> withMust = cs.stream().filter(c -> !c.path("expected").path("mustInclude").isEmpty()).toList();
        m.put("g.mustInclude", ratio(count(withMust, c -> c.path("checks").path("mustIncludeMissing").isEmpty()), withMust.size()));
        List<JsonNode> withForbidden = cs.stream().filter(c -> !c.path("expected").path("forbidden").isEmpty()).toList();
        m.put("g.forbiddenClean", ratio(count(withForbidden, c -> c.path("checks").path("forbiddenHits").isEmpty()), withForbidden.size()));
        m.put("g.routing", nullableRatio(cs, "routingOk", true));
        m.put("g.abstain", nullableRatio(cs, "abstained", true));
        List<JsonNode> judged = cs.stream().filter(c -> c.path("judge").has("grounded")).toList();
        m.put("g.grounded", ratio(count(judged, c -> c.path("judge").path("grounded").asBoolean()), judged.size()));
        m.put("g.deflection", ratio(count(cs, c -> c.path("checks").path("deflected").asBoolean()), cs.size()));
        m.put("g.error", ratio(count(cs, c -> "ERROR".equals(c.path("agent").path("outcome").asText())), cs.size()));

        List<Long> latencies = cs.stream().map(c -> c.path("agent").path("latencyMs").asLong()).sorted().toList();
        m.put("c.latencyP50Ms", percentile(latencies, 0.50));
        m.put("c.latencyP95Ms", percentile(latencies, 0.95));
        m.put("c.retrievalAvgMs", avg(cs, c -> c.path("retrieval").path("latencyMs").asDouble()));
        m.put("c.llmCallsAvg", avg(cs, c -> c.path("agent").path("llmCalls").asDouble()));
        m.put("c.promptTokensAvg", avg(cs, c -> c.path("agent").path("promptTokens").asDouble()));
        m.put("c.completionTokensAvg", avg(cs, c -> c.path("agent").path("completionTokens").asDouble()));
        m.put("c.cachedTokensAvg", avg(cs, c -> c.path("agent").path("cachedTokens").asDouble()));
        m.put("c.judgeTokensTotal", cs.stream().mapToLong(c ->
                c.path("judge").path("promptTokens").asLong() + c.path("judge").path("completionTokens").asLong()).sum());
        return m;
    }

    /** stale 마커가 있는 케이스만 분모로 센다. 마커는 케이스 정의에만 있어 원자료의 type 으로 가른다. */
    private static boolean isStaleCase(JsonNode c) {
        return "STALE".equals(c.path("type").asText());
    }

    private static Map<String, Object> nullableRatio(List<JsonNode> cs, String check, boolean wanted) {
        List<JsonNode> applicable = cs.stream().filter(c -> c.path("checks").path(check).isBoolean()).toList();
        return ratio(count(applicable, c -> c.path("checks").path(check).asBoolean() == wanted), applicable.size());
    }

    private static Map<String, Object> ratio(long num, long den) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("num", num);
        r.put("den", den);
        r.put("value", den == 0 ? null : Math.round(num * 1000.0 / den) / 1000.0);
        return r;
    }

    private static long count(List<JsonNode> cs, Predicate<JsonNode> p) {
        return cs.stream().filter(p).count();
    }

    private static double avg(List<JsonNode> cs, java.util.function.ToDoubleFunction<JsonNode> f) {
        return Math.round(cs.stream().mapToDouble(f).average().orElse(0) * 10) / 10.0;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static Stream<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out.stream();
    }

    private static Stream<Boolean> bools(JsonNode array) {
        List<Boolean> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asBoolean()));
        return out.stream();
    }

    // ---------- 비교 ----------

    private static Optional<JsonNode> previousMetrics(Path runsDir, Path current, ObjectMapper mapper) throws IOException {
        if (!Files.isDirectory(runsDir)) {
            return Optional.empty();
        }
        try (Stream<Path> dirs = Files.list(runsDir)) {
            Optional<Path> prev = dirs
                    .filter(p -> !p.equals(current) && Files.exists(p.resolve("metrics.json")))
                    .filter(p -> p.getFileName().toString().compareTo(current.getFileName().toString()) < 0)
                    .max(Path::compareTo);
            if (prev.isEmpty()) {
                return Optional.empty();
            }
            JsonNode node = mapper.readTree(prev.get().resolve("metrics.json").toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("dir", prev.get().getFileName().toString());
            return Optional.of(node);
        }
    }

    // ---------- 요약 ----------

    private static String summary(Map<String, Object> meta, Map<String, Map<String, Object>> metrics,
                                  Optional<JsonNode> previous, List<JsonNode> cases) {
        StringBuilder sb = new StringBuilder("# 실측 RAG 평가 결과\n\n");
        sb.append("| 조건 | 값 |\n|---|---|\n");
        meta.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        sb.append("""

                > 평가셋은 합성이다(작성자가 매뉴얼을 보고 만든 질문). judge 는 에이전트와 같은 모델이라
                > 자기 답에 관대할 수 있다. 수치는 판정이 아니라 다음 변경과 비교할 기준선이다.

                """);

        sb.append("## 단계별 지표\n\n").append(header());
        RATIO_LABELS.forEach((key, label) -> {
            sb.append("| ").append(label).append(" |");
            for (String g : GROUPS) {
                sb.append(' ').append(formatRatio(metrics.get(g).get(key))).append(" |");
            }
            sb.append('\n');
        });
        sb.append("\n## 비용·지연\n\n").append(header());
        COST_LABELS.forEach((key, label) -> {
            sb.append("| ").append(label).append(" |");
            for (String g : GROUPS) {
                boolean empty = ((Integer) metrics.get(g).get("caseCount")) == 0;
                sb.append(' ').append(empty ? "—" : metrics.get(g).get(key)).append(" |");
            }
            sb.append('\n');
        });

        previous.ifPresent(prev -> {
            sb.append("\n## 직전 실행 대비 (").append(prev.path("dir").asText()).append(")\n\n")
                    .append("셀은 `이전 → 이번`. 분모가 다르면 평가셋이 바뀐 것이니 직접 비교하지 않는다.\n\n")
                    .append(header());
            RATIO_LABELS.forEach((key, label) -> {
                sb.append("| ").append(label).append(" |");
                for (String g : GROUPS) {
                    JsonNode before = prev.path("metrics").path(g).path(key);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> now = (Map<String, Object>) metrics.get(g).get(key);
                    sb.append(' ').append(before.path("value").isNull() || before.isMissingNode()
                            ? "—" : before.path("value").asText())
                            .append(" → ").append(now.get("value") == null ? "—" : now.get("value")).append(" |");
                }
                sb.append('\n');
            });
        });

        sb.append("\n## 실패 케이스\n\n");
        for (JsonNode c : cases) {
            List<String> fails = failures(c);
            if (!fails.isEmpty()) {
                String answer = c.path("agent").path("answer").asText().replace("\n", " ");
                sb.append("- **").append(c.path("id").asText()).append("** ").append(c.path("query").asText())
                        .append("\n  - ").append(String.join(" · ", fails))
                        .append("\n  - 답변(")
                        .append(c.path("agent").path("outcome").asText()).append("): ")
                        .append(answer.length() > 160 ? answer.substring(0, 160) + "…" : answer).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<String> failures(JsonNode c) {
        List<String> f = new ArrayList<>();
        JsonNode checks = c.path("checks");
        List<Integer> ranks = ints(c.path("retrieval").path("evidenceRanks")).toList();
        if (ranks.stream().anyMatch(r -> r == 0 || r > 3)) {
            f.add("검색 근거 순위 " + ranks);
        }
        if (!c.path("retrieval").path("staleLeaks").isEmpty()) {
            f.add("검색 stale 노출 " + c.path("retrieval").path("staleLeaks"));
        }
        if (!checks.path("mustIncludeMissing").isEmpty()) {
            f.add("필수 사실 누락 " + checks.path("mustIncludeMissing"));
        }
        if (!checks.path("forbiddenHits").isEmpty()) {
            f.add("금지 문구 " + checks.path("forbiddenHits"));
        }
        if (checks.path("routingOk").isBoolean() && !checks.path("routingOk").asBoolean()) {
            f.add("라우팅 기대 " + c.path("expected").path("routing").asText());
        }
        if (checks.path("toolsOk").isBoolean() && !checks.path("toolsOk").asBoolean()) {
            f.add("필수 도구 미호출 " + c.path("agent").path("tools"));
        }
        if (checks.path("abstained").isBoolean() && !checks.path("abstained").asBoolean()) {
            f.add("근거 없이 답함");
        }
        if (checks.path("deflected").asBoolean()) {
            f.add("고객센터 떠넘기기");
        }
        if (c.path("judge").has("grounded") && !c.path("judge").path("grounded").asBoolean()) {
            f.add("judge 근거 불일치 " + c.path("judge").path("unsupportedClaims"));
        }
        if (c.path("agent").has("error")) {
            f.add("오류 " + c.path("agent").path("error").asText());
        }
        return f;
    }

    private static String header() {
        return "| 지표 | 개정·종료 | 다른 표현 | 복합 | 인젝션 | 범위 밖 | 전체 |\n|---|---|---|---|---|---|---|\n";
    }

    @SuppressWarnings("unchecked")
    private static String formatRatio(Object o) {
        Map<String, Object> r = (Map<String, Object>) o;
        if (r.get("value") == null) {
            return "—";
        }
        return r.get("value") + " (" + r.get("num") + "/" + r.get("den") + ")";
    }

    private static Map<String, String> labels(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---------- 실행 조건 ----------

    public static String gitDescribe() {
        String sha = run("git", "rev-parse", "--short", "HEAD").orElse("nogit");
        boolean dirty = run("git", "status", "--porcelain").map(s -> !s.isBlank()).orElse(false);
        return dirty ? sha + "-dirty" : sha;
    }

    private static Optional<String> run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256File(Path path) {
        try {
            return sha256(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
