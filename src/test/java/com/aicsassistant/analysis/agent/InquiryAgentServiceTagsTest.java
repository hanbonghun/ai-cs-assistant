package com.aicsassistant.analysis.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import org.junit.jupiter.api.Test;

/**
 * Langfuse trace attribute 헬퍼(buildTags / safeUserId) 순수 함수 검증.
 */
class InquiryAgentServiceTagsTest {

    // buildTags 는 분석 결과(category/urgency 문자열)를 받는다. 이전에는 Inquiry 를 받았는데,
    // span 생성 시점의 Inquiry 는 아직 분석 전이라 둘 다 null 이어서 태그가 항상 비어 있었다.

    @Test
    void buildTags_returnsEmptyList_whenCategoryAndUrgencyNull() {
        assertThat(InquiryAgentService.buildTags(null, null)).isEmpty();
    }

    @Test
    void buildTags_returnsEmptyList_whenBlank() {
        assertThat(InquiryAgentService.buildTags("", "  ")).isEmpty();
    }

    @Test
    void buildTags_includesCategoryOnly_whenUrgencyNull() {
        assertThat(InquiryAgentService.buildTags(InquiryCategory.REFUND.name(), null))
                .containsExactly("category:REFUND");
    }

    @Test
    void buildTags_includesUrgencyOnly_whenCategoryNull() {
        assertThat(InquiryAgentService.buildTags(null, UrgencyLevel.HIGH.name()))
                .containsExactly("urgency:HIGH");
    }

    @Test
    void buildTags_includesBoth_inOrder() {
        assertThat(InquiryAgentService.buildTags(
                InquiryCategory.EXCHANGE.name(), UrgencyLevel.MEDIUM.name()))
                .containsExactly("category:EXCHANGE", "urgency:MEDIUM");
    }

    @Test
    void safeUserId_returnsAnonymous_whenCustomerNull() {
        Inquiry inquiry = Inquiry.create(null, "t", "c");

        assertThat(InquiryAgentService.safeUserId(inquiry)).isEqualTo("anonymous");
    }

    @Test
    void safeUserId_returnsAnonymous_whenCustomerBlank() {
        Inquiry inquiry = Inquiry.create("   ", "t", "c");

        assertThat(InquiryAgentService.safeUserId(inquiry)).isEqualTo("anonymous");
    }

    @Test
    void safeUserId_returnsRawId_whenCustomerSet() {
        Inquiry inquiry = Inquiry.create("kim-minjun", "t", "c");

        assertThat(InquiryAgentService.safeUserId(inquiry)).isEqualTo("kim-minjun");
    }
}
