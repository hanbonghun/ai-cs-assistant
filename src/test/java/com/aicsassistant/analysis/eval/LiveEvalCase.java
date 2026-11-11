package com.aicsassistant.analysis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실측 평가셋 한 건. 필드의 의미는 {@code src/test/resources/eval/live/README.md} 에 있다.
 *
 * <p>날짜는 문자열로 박지 않고 {@code {D+20}} 같은 자리표시자로 쓴다. 에이전트는 실행일을
 * {@code [오늘]} 로 받으므로, 고정 날짜를 쓰면 "이미 끝난 행사" 케이스가 날짜가 지나면서 저절로
 * 뜻이 바뀐다. 픽스처 문서와 케이스가 같은 기준일로 풀려야 한다.
 */
record LiveEvalCase(
        String id,
        Type type,
        String customerId,
        String query,
        List<String> expectedEvidence,
        List<String> staleMarkers,
        List<String> mustInclude,
        List<String> forbidden,
        Routing expectedRouting,
        List<String> mustCallTools,
        boolean expectAbstain,
        String note
) {

    enum Type { STALE, PARAPHRASE, COMPOSITE, INJECTION, OUT_OF_SCOPE }

    /**
     * 기대 라우팅. {@code NOT_AUTO} 는 상담사 이관이나 추가 질문 어느 쪽이든 자동 답변만 아니면 된다.
     */
    enum Routing { AUTO, HUMAN, NOT_AUTO, ANY }

    private static final Pattern DATE_TOKEN = Pattern.compile("\\{D([+-]\\d+)(:md)?}");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M월 d일");

    static List<LiveEvalCase> load(Path path, LocalDate today) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            List<LiveEvalCase> cases = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                if (!line.isBlank()) {
                    cases.add(parse(mapper.readTree(line), today));
                }
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load live eval set: " + path, e);
        }
    }

    private static LiveEvalCase parse(JsonNode n, LocalDate today) {
        return new LiveEvalCase(
                n.path("id").asText(),
                Type.valueOf(n.path("type").asText()),
                n.path("customerId").asText(),
                resolveDates(n.path("query").asText(), today),
                list(n, "expectedEvidence", today),
                list(n, "staleMarkers", today),
                list(n, "mustInclude", today),
                list(n, "forbidden", today),
                Routing.valueOf(n.path("expectedRouting").asText("ANY")),
                list(n, "mustCallTools", today),
                n.path("expectAbstain").asBoolean(false),
                n.path("note").asText(""));
    }

    private static List<String> list(JsonNode n, String field, LocalDate today) {
        List<String> values = new ArrayList<>();
        n.path(field).forEach(v -> values.add(resolveDates(v.asText(), today)));
        return List.copyOf(values);
    }

    /** {@code {D-3}} → {@code 2026-11-09}, {@code {D-3:md}} → {@code 11월 9일}. */
    static String resolveDates(String raw, LocalDate today) {
        Matcher m = DATE_TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            LocalDate date = today.plusDays(Long.parseLong(m.group(1)));
            String formatted = m.group(2) == null ? date.toString() : date.format(MONTH_DAY);
            m.appendReplacement(out, Matcher.quoteReplacement(formatted));
        }
        m.appendTail(out);
        return out.toString();
    }
}
