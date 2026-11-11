package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KoreanQueryPreprocessorTest {

    @Test
    void replacesCustomerExpressionWithManualTerm() {
        assertThat(KoreanQueryPreprocessor.applyAliases("적립금 소멸돼요?")).isEqualTo("포인트 소멸돼요?");
        assertThat(KoreanQueryPreprocessor.forKeywordSearch("택배비 누가 내요")).contains("배송비");
    }

    @Test
    void longerAliasWinsOverItsPart() {
        // "택배비" 가 "택비" 보다 먼저 바뀌어야 "택배비" 안의 문자열이 이중으로 바뀌지 않는다
        assertThat(KoreanQueryPreprocessor.applyAliases("택배비 얼마")).isEqualTo("배송비 얼마");
    }

    @Test
    void skipsAliasWhenCanonicalTermIsAlreadyThere() {
        assertThat(KoreanQueryPreprocessor.applyAliases("재입고 알림 신청")).isEqualTo("재입고 알림 신청");
    }

    @Test
    void leavesQueriesWithoutAliasesUntouched() {
        assertThat(KoreanQueryPreprocessor.applyAliases("반품 기간 알려주세요")).isEqualTo("반품 기간 알려주세요");
    }
}
