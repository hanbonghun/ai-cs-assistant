package com.aicsassistant.analysis.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 실측 평가의 규칙 기반 채점. 모두 순수 함수라 API 없이 단위 테스트한다.
 *
 * <p>문자열 비교는 공백과 쉼표를 지운 뒤 한다 — "3,000원" · "3000원" · "3 000 원" 을 같게 보려는
 * 것이다. 대신 "3천원" 같은 다른 표기는 케이스 쪽에서 {@code |} 로 대안을 적는다. 정규화를 더
 * 똑똑하게 만들면 채점기가 틀렸을 때 원인을 찾기 어려워진다.
 */
final class LiveEvalScorer {

    /** 모델이 매뉴얼 문장을 그대로 옮길 때 자주 섞여 나오는 "다른 데 연락하라" 류. CLAUDE.md 가 금지한다. */
    private static final List<String> DEFLECTIONS = List.of(
            "고객센터에연락", "고객센터로연락", "고객센터에문의", "고객센터로문의", "고객센터에신고", "고객센터에제출");

    /**
     * 근거가 없다고 밝힌 표현. 범위 밖 질문에서 "모른다/이관" 을 했는지 보는 데 쓴다. 넓게 잡았으므로
     * 이것만으로 통과를 판정하지 않는다 — 지어냈는지는 judge 가 따로 본다.
     */
    private static final List<String> ABSTAIN_MARKERS = List.of(
            "확인되지", "확인이어렵", "확인할수없", "안내드리기어렵", "안내해드리기어렵", "답변드리기어렵",
            "제공하지않", "제공되지않", "지원하지않", "지원되지않", "운영하지않", "정보가없", "찾을수없",
            "도와드리기어렵", "범위를벗어", "담당자", "상담사");

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
        String normalized = normalize(answer);
        return ABSTAIN_MARKERS.stream().anyMatch(normalized::contains);
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
