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

    @Test
    void buildTags_returnsEmptyList_whenCategoryAndUrgencyNull() {
        Inquiry inquiry = Inquiry.create("kim", "t", "c");

        assertThat(InquiryAgentService.buildTags(inquiry)).isEmpty();
    }

    @Test
    void buildTags_includesCategoryOnly_whenUrgencyNull() {
        Inquiry inquiry = Inquiry.create("kim", "t", "c", InquiryCategory.REFUND, null);

        assertThat(InquiryAgentService.buildTags(inquiry))
                .containsExactly("category:REFUND");
    }

    @Test
    void buildTags_includesUrgencyOnly_whenCategoryNull() {
        Inquiry inquiry = Inquiry.create("kim", "t", "c", null, UrgencyLevel.HIGH);

        assertThat(InquiryAgentService.buildTags(inquiry))
                .containsExactly("urgency:HIGH");
    }

    @Test
    void buildTags_includesBoth_inOrder() {
        Inquiry inquiry = Inquiry.create("kim", "t", "c", InquiryCategory.EXCHANGE, UrgencyLevel.MEDIUM);

        assertThat(InquiryAgentService.buildTags(inquiry))
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
