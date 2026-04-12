package com.aicsassistant.inquiry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.dto.CreateInquiryRequest;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import com.aicsassistant.inquiry.dto.InquiryListResponse;
import com.aicsassistant.inquiry.dto.ReviewInquiryRequest;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class InquiryServiceTest extends PostgresVectorIntegrationTest {

    @Autowired
    InquiryService inquiryService;

    @Autowired
    ReviewService reviewService;

    @Autowired
    InquiryRepository inquiryRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void createsAndListsInquiriesWithOptionalFilters() {
        inquiryService.create(new CreateInquiryRequest(
                "cust-001",
                "환불 문의",
                "환불 가능한가요?",
                InquiryCategory.REFUND,
                UrgencyLevel.HIGH
        ));
        inquiryService.create(new CreateInquiryRequest(
                "cust-002",
                "주문 취소 문의",
                "주문 취소 가능한가요?",
                InquiryCategory.ORDER,
                UrgencyLevel.LOW
        ));

        List<InquiryListResponse> filtered = inquiryService.getInquiries(InquiryStatus.NEW, InquiryCategory.REFUND, UrgencyLevel.HIGH);

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).category()).isEqualTo(InquiryCategory.REFUND);
        assertThat(filtered.get(0).urgency()).isEqualTo(UrgencyLevel.HIGH);
    }

    @Test
    void returnsInquiryDetailWithRecentAnalysisLogs() {
        Inquiry inquiry = inquiryRepository.save(Inquiry.create("cust-010", "환불 문의", "환불 가능한가요?"));
        jdbcTemplate.update("""
                insert into inquiry_analysis_log (
                    inquiry_id, request_snapshot, classified_category, classified_urgency, retrieved_chunk_ids,
                    generated_draft, model_name, prompt_version, analysis_status, error_message, latency_ms, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                inquiry.getId(),
                "환불 가능한가요?",
                "REFUND",
                "HIGH",
                "1,2,3",
                "초안 답변",
                "gpt-test",
                "v1",
                "SUCCESS",
                null,
                321L
        );

        InquiryDetailResponse response = inquiryService.getInquiry(inquiry.getId());

        assertThat(response.id()).isEqualTo(inquiry.getId());
        assertThat(response.analysisLogs()).hasSize(1);
        assertThat(response.analysisLogs().get(0).generatedDraft()).isEqualTo("초안 답변");
    }

    @Test
    void closesOnlyReviewedInquiry() {
        Inquiry inquiry = inquiryRepository.save(Inquiry.create("cust-020", "문의", "답변 부탁드립니다."));
        inquiry.markAiProcessed();
        Inquiry saved = inquiryRepository.save(inquiry);
        reviewService.confirm(saved.getId(), new ReviewInquiryRequest("최종 답변", null, "mimi"));

        inquiryService.close(saved.getId());

        Inquiry reloaded = inquiryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.CLOSED);
    }

    @Test
    void throwsNotFoundForMissingInquiryDetail() {
        assertThatThrownBy(() -> inquiryService.getInquiry(9999L))
                .isInstanceOf(ApiException.class);
    }
}
