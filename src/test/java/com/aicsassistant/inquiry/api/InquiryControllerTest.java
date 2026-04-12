package com.aicsassistant.inquiry.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicsassistant.common.exception.GlobalExceptionHandler;
import com.aicsassistant.inquiry.application.InquiryService;
import com.aicsassistant.inquiry.application.ReviewService;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import com.aicsassistant.inquiry.dto.InquiryListResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InquiryController.class)
@Import(GlobalExceptionHandler.class)
class InquiryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InquiryService inquiryService;

    @MockitoBean
    ReviewService reviewService;

    @Test
    void createsInquiry() throws Exception {
        given(inquiryService.create(eq(new com.aicsassistant.inquiry.dto.CreateInquiryRequest(
                "cust-001",
                "환불 문의",
                "환불 가능한가요?",
                InquiryCategory.REFUND,
                UrgencyLevel.HIGH,
                null
        )))).willReturn(new InquiryDetailResponse(
                1L,
                "cust-001",
                "환불 문의",
                "환불 가능한가요?",
                InquiryCategory.REFUND,
                UrgencyLevel.HIGH,
                InquiryStatus.NEW,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 4, 8, 10, 0),
                LocalDateTime.of(2026, 4, 8, 10, 0),
                List.of()
        ));

        mockMvc.perform(post("/api/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerIdentifier": "cust-001",
                                  "title": "환불 문의",
                                  "content": "환불 가능한가요?",
                                  "category": "REFUND",
                                  "urgency": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void listsInquiries() throws Exception {
        given(inquiryService.getInquiries(InquiryStatus.NEW, InquiryCategory.REFUND, UrgencyLevel.HIGH))
                .willReturn(List.of(new InquiryListResponse(
                        1L,
                        "cust-001",
                        "환불 문의",
                        InquiryCategory.REFUND,
                        UrgencyLevel.HIGH,
                        InquiryStatus.NEW,
                        LocalDateTime.of(2026, 4, 8, 10, 0),
                        LocalDateTime.of(2026, 4, 8, 10, 0)
                )));

        mockMvc.perform(get("/api/inquiries")
                        .param("status", "NEW")
                        .param("category", "REFUND")
                        .param("urgency", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].category").value("REFUND"));
    }

    @Test
    void returnsInquiryDetailIncludingAnalysisLogs() throws Exception {
        given(inquiryService.getInquiry(1L)).willReturn(new InquiryDetailResponse(
                1L,
                "cust-001",
                "환불 문의",
                "환불 가능한가요?",
                InquiryCategory.REFUND,
                UrgencyLevel.HIGH,
                InquiryStatus.AI_PROCESSED,
                "초안 답변",
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 4, 8, 10, 0),
                LocalDateTime.of(2026, 4, 8, 10, 5),
                List.of(new InquiryDetailResponse.AnalysisLogSummary(
                        10L,
                        "SUCCESS",
                        InquiryCategory.REFUND,
                        UrgencyLevel.HIGH,
                        "초안 답변",
                        "gpt-test",
                        "v1",
                        321L,
                        LocalDateTime.of(2026, 4, 8, 10, 3)
                ))
        ));

        mockMvc.perform(get("/api/inquiries/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisLogs[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.analysisLogs[0].generatedDraft").value("초안 답변"));
    }

    @Test
    void confirmsReview() throws Exception {
        mockMvc.perform(post("/api/inquiries/1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "finalAnswer": "최종 답변",
                                  "reviewMemo": "근거 확인",
                                  "confirmedBy": "mimi"
                                }
                                """))
                .andExpect(status().isOk());

        verify(reviewService).confirm(1L, new com.aicsassistant.inquiry.dto.ReviewInquiryRequest("최종 답변", "근거 확인", "mimi"));
    }

    @Test
    void closesInquiry() throws Exception {
        mockMvc.perform(post("/api/inquiries/1/close"))
                .andExpect(status().isOk());

        verify(inquiryService).close(1L);
    }
}
