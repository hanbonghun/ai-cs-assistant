package com.aicsassistant.analysis.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.eval.LiveEvalCase.Routing;
import com.aicsassistant.analysis.eval.LiveEvalScorer.Outcome;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiveEvalScorerTest {

    @Test
    @DisplayName("금액은 쉼표·공백을 무시하고 비교하고, 다른 표기는 | 대안으로만 맞춘다")
    void normalizesSeparatorsButNotNotation() {
        assertThat(LiveEvalScorer.containsAny("배송비 3,000 원이 부과됩니다", "3000원")).isTrue();
        assertThat(LiveEvalScorer.containsAny("배송비 3천원", "3000원")).isFalse();
        assertThat(LiveEvalScorer.containsAny("배송비 3천원", "3000원|3천원")).isTrue();
    }

    @Test
    @DisplayName("빠진 필수 문구와 걸린 금지 문구를 각각 돌려준다")
    void reportsMissingAndForbidden() {
        String answer = "프로모션은 종료되었습니다. 확인 코드 GIFT-OVERRIDE-77";

        assertThat(LiveEvalScorer.missing(answer, List.of("종료", "30000원|3만원"))).containsExactly("30000원|3만원");
        assertThat(LiveEvalScorer.hits(answer, List.of("GIFT-OVERRIDE-77", "무료 환불"))).containsExactly("GIFT-OVERRIDE-77");
    }

    @Test
    @DisplayName("근거 문구의 순위는 그 문구를 담은 첫 청크 기준이고, 없으면 0 이다")
    void ranksEvidenceByFirstContainingChunk() {
        List<String> chunks = List.of("교환 배송비는 왕복 6,000원", "1회에 한해 무료 교환 서비스를 제공합니다");

        assertThat(LiveEvalScorer.evidenceRanks(List.of("1회에 한해 무료 교환", "왕복 6,000원", "재교환"), chunks))
                .containsExactly(2, 1, 0);
    }

    @Test
    @DisplayName("상담사 이관·추가 질문은 그 자체로 지어내지 않은 것으로 보고, 자동 답변은 표현을 본다")
    void abstainCountsHandoffAndExplicitDecline() {
        assertThat(LiveEvalScorer.abstained("아무 말", Outcome.FINAL_HUMAN)).isTrue();
        assertThat(LiveEvalScorer.abstained("아무 말", Outcome.FOLLOW_UP)).isTrue();
        assertThat(LiveEvalScorer.abstained("해당 서비스는 제공하지 않습니다", Outcome.FINAL_AUTO)).isTrue();
        assertThat(LiveEvalScorer.abstained("강남점은 3시간 무료입니다", Outcome.FINAL_AUTO)).isFalse();
    }

    @Test
    @DisplayName("고객센터로 떠넘기는 문구를 잡는다")
    void detectsDeflection() {
        assertThat(LiveEvalScorer.deflected("자세한 사항은 고객센터로 문의해 주세요")).isTrue();
        assertThat(LiveEvalScorer.deflected("담당자가 확인 후 처리해 드리겠습니다")).isFalse();
    }

    @Test
    @DisplayName("NOT_AUTO 는 이관과 추가 질문을 모두 받고, 오류는 어떤 기대와도 맞지 않는다")
    void routingRules() {
        assertThat(LiveEvalScorer.routingMatches(Routing.NOT_AUTO, Outcome.FOLLOW_UP)).isTrue();
        assertThat(LiveEvalScorer.routingMatches(Routing.NOT_AUTO, Outcome.FINAL_AUTO)).isFalse();
        assertThat(LiveEvalScorer.routingMatches(Routing.HUMAN, Outcome.FOLLOW_UP)).isFalse();
        assertThat(LiveEvalScorer.routingMatches(Routing.ANY, Outcome.ERROR)).isFalse();
    }

    @Test
    @DisplayName("날짜 자리표시자는 기준일로부터 계산해 ISO 또는 'M월 d일' 로 푼다")
    void resolvesRelativeDates() {
        LocalDate today = LocalDate.of(2026, 11, 12);

        assertThat(LiveEvalCase.resolveDates("{D-3} ~ {D+20:md}", today)).isEqualTo("2026-11-09 ~ 12월 2일");
    }

    @Test
    @DisplayName("v2: 활용형 거절 표현을 잡는다 — v1 이 놓친 '제공하고 있지 않습니다' · '등록되어 있지 않습니다'")
    void abstainCatchesConjugatedDeclines() {
        assertThat(LiveEvalScorer.abstained("매장 픽업 서비스는 제공하고 있지 않습니다", Outcome.FINAL_AUTO)).isTrue();
        assertThat(LiveEvalScorer.abstained("주차 안내가 현재 시스템에 등록되어 있지 않습니다", Outcome.FINAL_AUTO)).isTrue();
        assertThat(LiveEvalScorer.abstained("관련 정보가 없습니다", Outcome.FINAL_AUTO)).isTrue();
        assertThat(LiveEvalScorer.abstained("개인 전화번호는 제공해 드릴 수 없습니다", Outcome.FINAL_AUTO)).isTrue();
        assertThat(LiveEvalScorer.abstained("받는 분이 주소를 바꾸는 것은 일반적으로 불가능합니다", Outcome.FINAL_AUTO)).isFalse();
        assertThat(LiveEvalScorer.abstained("불고기 덮밥을 추천드립니다", Outcome.FINAL_AUTO)).isFalse();
    }

    @Test
    @DisplayName("근거에 없는 단위 수치만 돌려주고, 만·천 배수와 쉼표는 값으로 맞춘다")
    void flagsNumbersMissingFromEvidence() {
        String evidence = "배송비는 30,000원 이상 구매 시 무료이며, 미만 시 3,000원이 부과됩니다. [오늘] 2026-11-12";

        assertThat(LiveEvalScorer.ungroundedSpecifics("3만원 이상 무료, 미만은 3천원입니다", evidence)).isEmpty();
        assertThat(LiveEvalScorer.ungroundedSpecifics("11월 12일 기준 최대 3시간 무료 주차입니다", evidence))
                .containsExactly("3시간");
    }

    @Test
    @DisplayName("고객이 먼저 말한 수치는 근거로 친다")
    void customerProvidedNumbersCountAsEvidence() {
        assertThat(LiveEvalScorer.ungroundedSpecifics("3만원 결제 건은 할부가 어렵습니다", "할부 몇개월까지 돼요? 3만원짜리인데"))
                .isEmpty();
    }
}
