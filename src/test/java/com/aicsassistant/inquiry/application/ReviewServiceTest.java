package com.aicsassistant.inquiry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.dto.ReviewInquiryRequest;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ReviewServiceTest extends PostgresVectorIntegrationTest {

    @Autowired
    InquiryRepository inquiryRepository;

    @Autowired
    ReviewService reviewService;

    @Autowired
    InquiryService inquiryService;

    @Test
    void reviewMovesInquiryToReviewedAndPersistsAnswer() {
        Inquiry inquiry = inquiryRepository.save(Inquiry.create("cust-001", "문의", "환불 가능한가요?"));
        inquiry.markAiProcessed();
        Inquiry saved = inquiryRepository.save(inquiry);

        reviewService.confirm(saved.getId(), new ReviewInquiryRequest("환불 규정상 ...", "정책 근거 확인", "mimi"));

        Inquiry reloaded = inquiryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.REVIEWED);
        assertThat(reloaded.getFinalAnswer()).isEqualTo("환불 규정상 ...");
        assertThat(reloaded.getReviewMemo()).isEqualTo("정책 근거 확인");
        assertThat(reloaded.getReviewedBy()).isEqualTo("mimi");
    }

    @Test
    void reviewRejectsNewInquiry() {
        Inquiry inquiry = inquiryRepository.save(Inquiry.create("cust-003", "문의", "상담 가능한가요?"));

        assertThatThrownBy(() -> reviewService.confirm(
                inquiry.getId(),
                new ReviewInquiryRequest("답변", null, "mimi")
        )).isInstanceOf(ApiException.class);
    }

    @Test
    void closeRejectsNewAndAiProcessedInquiries() {
        Inquiry newInquiry = inquiryRepository.save(Inquiry.create("cust-002", "문의", "지금 취소 가능한가요?"));
        Inquiry aiProcessed = inquiryRepository.save(Inquiry.create("cust-004", "문의", "예약 변경 가능한가요?"));
        aiProcessed.markAiProcessed();
        inquiryRepository.save(aiProcessed);

        assertThatThrownBy(() -> inquiryService.close(newInquiry.getId()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inquiryService.close(aiProcessed.getId()))
                .isInstanceOf(ApiException.class);
    }
}
