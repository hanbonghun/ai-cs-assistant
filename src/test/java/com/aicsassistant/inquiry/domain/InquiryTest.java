package com.aicsassistant.inquiry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.common.exception.ApiException;
import org.junit.jupiter.api.Test;

class InquiryTest {

    @Test
    void agentAnswerWithoutHumanReviewIsAutoAnswered() {
        Inquiry inquiry = Inquiry.create("cust-001", "배송", "언제 오나요?");

        inquiry.applyAgentAnswer(InquiryCategory.DELIVERY, UrgencyLevel.LOW, "내일 도착합니다.", false);

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.AUTO_ANSWERED);
        assertThat(inquiry.getFinalAnswer()).isEqualTo("내일 도착합니다.");
    }

    @Test
    void agentAnswerNeedingHumanStaysForReview() {
        Inquiry inquiry = Inquiry.create("cust-001", "환불", "환불해 주세요");

        inquiry.applyAgentAnswer(InquiryCategory.REFUND, UrgencyLevel.HIGH, "확인 후 안내드립니다.", true);

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.AI_PROCESSED);
        assertThat(inquiry.getFinalAnswer()).isNull();
    }

    @Test
    void customerReplyRequiresPendingCustomer() {
        Inquiry inquiry = Inquiry.create("cust-001", "문의", "주문 문의");

        assertThatThrownBy(() -> inquiry.replyAsCustomer("ORD-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PENDING_CUSTOMER");
    }

    @Test
    void customerReplyIsStrippedIntoCustomerMessage() {
        Inquiry inquiry = Inquiry.create("cust-001", "문의", "주문 문의");
        inquiry.askFollowUp();

        InquiryMessage message = inquiry.replyAsCustomer("  ORD-1  ");

        assertThat(message.getRole()).isEqualTo(InquiryMessageRole.CUSTOMER);
        assertThat(message.getContent()).isEqualTo("ORD-1");
    }

    @Test
    void autoAnsweredInquiryCanBeClosed() {
        Inquiry inquiry = Inquiry.create("cust-001", "배송", "언제 오나요?");
        inquiry.applyAgentAnswer(InquiryCategory.DELIVERY, UrgencyLevel.LOW, "내일 도착합니다.", false);

        inquiry.close();

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.CLOSED);
    }
}
