package com.aicsassistant.analysis.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실측 평가의 규칙 기반 채점. 모두 순수 함수라 API 없이 단위 테스트한다.
 *
 * <p>문자열 비교는 공백과 쉼표를 지운 뒤 한다 — "3,000원" · "3000원" · "3 000 원" 을 같게 보려는
 * 것이다. 대신 "3천원" 같은 다른 표기는 케이스 쪽에서 {@code |} 로 대안을 적는다. 정규화를 더
 * 똑똑하게 만들면 채점기가 틀렸을 때 원인을 찾기 어려워진다.
 */
final class LiveEvalScorer {

    /** 채점 규칙이 바뀌면 올린다. 실행 기록과 재채점 결과에 남아, 다른 버전끼리 섞어 비교하지 않게 한다. */
    static final String VERSION = "2";

    /** 모델이 매뉴얼 문장을 그대로 옮길 때 자주 섞여 나오는 "다른 데 연락하라" 류. CLAUDE.md 가 금지한다. */
    private static final List<String> DEFLECTIONS = List.of(
            "고객센터에연락", "고객센터로연락", "고객센터에문의", "고객센터로문의", "고객센터에신고", "고객센터에제출");

    /**
     * 근거가 없다고 밝힌 표현. 범위 밖 질문에서 "모른다/이관" 을 했는지 보는 데 쓴다.
     *
     * <p>v1 은 낱말 목록이었고 "제공하고 있지 않습니다" · "등록되어 있지 않습니다" 같은 활용형을 놓쳤다.
     * v2 는 동사 어간 + 부정 어미 패턴으로 잡는다. 정규화(공백 제거) 뒤의 문자열에 적용한다.
     * 넓게 잡으므로 이것만으로 통과를 판정하지 않는다 — 지어냈는지는 {@link #ungroundedSpecifics} 와 judge 가 따로 본다.
     */
    private static final Pattern ABSTAIN = Pattern.compile(String.join("|",
            "(제공|지원|운영|등록|안내|확인|진행|마련|취급|판매|보유)(하고|되고|되어|돼|해|하지|되지)?있지않",
            "(제공|지원|운영|등록|안내|확인|진행|마련|취급|판매|보유)(하지|되지|되어있지|돼있지)않",
            "(정보|안내|자료|문서|정책|내용|기능|서비스|규정)(가|이|는|은)?(현재|따로|별도로|별도의)?(없|존재하지않)",
            "확인(되지|이어렵|이불가|할수없|하기어렵|드리기어렵)",
            "(안내|답변|도와|도움을|제공|알려|확인)(해)?(드리기|드릴수|드릴수가)(어렵|없)",
            "범위를벗어", "알수없", "담당자", "상담사"));

    /** 금액·기간·횟수처럼 단위가 붙은 수치. "3만원" 의 만·천 배수를 값에 반영한다. */
    private static final Pattern UNIT_NUMBER = Pattern.compile(
            "(\\d[\\d,]*(?:\\.\\d+)?)\\s*(만|천)?\\s*(원|영업일|일|시간|개월|년|%|시|분|포인트|회|개|mm|cm)");
    private static final Pattern ANY_NUMBER = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(만|천)?");

    enum Outcome { FINAL_AUTO, FINAL_HUMAN, FOLLOW_UP, ERROR }

    private LiveEvalScorer() {
    }

    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s,]", "").toLowerCase(Locale.ROOT);
    }

    /** {@code "3000원|3천원"} 처럼 {@code |} 로 나눈 대안 중 하나라도 들어 있으면 참. */
    static boolean containsAny(String text, String alternatives) {
        String haystack = normalize(text);
        for (String alt : alternatives.split("\\|")) {
            String needle = normalize(alt);
            if (!needle.isEmpty() && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static List<String> missing(String answer, List<String> mustInclude) {
        return mustInclude.stream().filter(m -> !containsAny(answer, m)).toList();
    }

    static List<String> hits(String answer, List<String> forbidden) {
        return forbidden.stream().filter(f -> containsAny(answer, f)).toList();
    }

    /**
     * 근거 문구마다 그것을 담은 첫 청크의 순위(1부터). 없으면 0.
     *
     * <p>청크 id 가 아니라 본문 문구로 맞추는 이유: 청크 분할 규칙을 바꾸면 id 와 경계가 전부 바뀌는데,
     * 그때가 바로 전후를 비교하고 싶은 순간이다. 청커는 100자를 겹쳐 자르므로 100자 미만 문구는
     * 분할 방식과 무관하게 어느 한 청크에 통째로 들어간다.
     */
    static List<Integer> evidenceRanks(List<String> evidence, List<String> chunkContents) {
        List<Integer> ranks = new ArrayList<>();
        for (String phrase : evidence) {
            int rank = 0;
            for (int i = 0; i < chunkContents.size(); i++) {
                if (containsAny(chunkContents.get(i), phrase)) {
                    rank = i + 1;
                    break;
                }
            }
            ranks.add(rank);
        }
        return ranks;
    }

    static boolean deflected(String answer) {
        String normalized = normalize(answer);
        return DEFLECTIONS.stream().anyMatch(normalized::contains);
    }

    /** 근거 없음을 밝혔거나, 사람에게 넘겼거나, 되물었으면 "지어내지 않을 기회를 잡았다" 로 본다. */
    static boolean abstained(String answer, Outcome outcome) {
        if (outcome == Outcome.FINAL_HUMAN || outcome == Outcome.FOLLOW_UP) {
            return true;
        }
        return ABSTAIN.matcher(normalize(answer)).find();
    }

    /**
     * 근거 없이 단정한 수치. 답에 나온 "단위가 붙은 수치" 중 근거 텍스트 어디에도 그 값이 없는 것을 돌려준다.
     *
     * <p>judge 는 근거 안에 심긴 문구를 "근거 있음" 으로 보는 등 이미 놓친 전력이 있어, 수치만큼은 규칙으로 본다.
     * 값으로 비교하므로 "30,000원" 과 "3만원" 은 같다. 절차·기능 설명("설정 메뉴에서…")은 잡지 못한다 — 그쪽은
     * {@link #abstained} 와 judge 몫이다.
     *
     * @param evidence 고객 질문 + 오늘 날짜 + 에이전트가 본 도구 관측 전부. 고객이 먼저 말한 수치는 근거로 친다
     */
    static List<String> ungroundedSpecifics(String answer, String evidence) {
        Set<String> known = new HashSet<>();
        Matcher e = ANY_NUMBER.matcher(evidence == null ? "" : evidence);
        while (e.find()) {
            known.add(value(e.group(1), null));
            known.add(value(e.group(1), e.group(2)));
        }
        List<String> out = new ArrayList<>();
        Matcher a = UNIT_NUMBER.matcher(answer == null ? "" : answer);
        while (a.find()) {
            if (!known.contains(value(a.group(1), a.group(2)))) {
                out.add(a.group().strip());
            }
        }
        return out;
    }

    private static String value(String digits, String multiplier) {
        double v = Double.parseDouble(digits.replace(",", ""));
        if ("만".equals(multiplier)) {
            v *= 10_000;
        } else if ("천".equals(multiplier)) {
            v *= 1_000;
        }
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    /**
     * 케이스 한 건의 규칙 채점 전부. 실행기와 재채점기가 같이 쓴다 — 둘이 따로 계산하면 같은 실행을 다시
     * 매겼을 때 숫자가 어긋나도 원인이 채점 규칙인지 코드 중복인지 가를 수 없다.
     *
     * <p>"근거 0건" 은 에이전트가 모은 청크가 없고 주문 조회 도구도 부르지 않은 경우다. 조회 성공 여부는
     * 기준선 실행의 원자료에 남지 않아 호출 여부로 근사한다.
     */
    static Map<String, Object> checks(LiveEvalCase c, String answer, Outcome outcome, List<String> tools,
                                      List<String> collectedTitles, String today) {
        boolean noEvidence = collectedTitles.isEmpty() && !tools.contains("check_order_status");
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("scorerVersion", VERSION);
        checks.put("mustIncludeMissing", missing(answer, c.mustInclude()));
        checks.put("forbiddenHits", hits(answer, c.forbidden()));
        checks.put("routingOk", c.expectedRouting() == LiveEvalCase.Routing.ANY ? null : routingMatches(c.expectedRouting(), outcome));
        checks.put("toolsOk", c.mustCallTools().isEmpty() ? null : Set.copyOf(tools).containsAll(c.mustCallTools()));
        checks.put("abstained", c.expectAbstain() ? abstained(answer, outcome) : null);
        checks.put("deflected", deflected(answer));
        checks.put("noEvidence", noEvidence);
        checks.put("ungroundedSpecifics", noEvidence ? ungroundedSpecifics(answer, c.query() + " [오늘] " + today) : null);
        return checks;
    }

    static boolean routingMatches(LiveEvalCase.Routing expected, Outcome actual) {
        if (actual == Outcome.ERROR) {
            return false;
        }
        return switch (expected) {
            case ANY -> true;
            case AUTO -> actual == Outcome.FINAL_AUTO;
            case HUMAN -> actual == Outcome.FINAL_HUMAN;
            case NOT_AUTO -> actual != Outcome.FINAL_AUTO;
        };
    }
}
