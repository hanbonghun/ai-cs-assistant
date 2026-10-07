package com.aicsassistant.analysis.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicsassistant.analysis.application.AnalysisLogService;
import com.aicsassistant.analysis.application.InquiryAnalysisService;
import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.DraftAnswerDto;
import com.aicsassistant.analysis.dto.InquiryAnalysisLogResponse;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.common.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InquiryAnalysisController.class)
@Import(GlobalExceptionHandler.class)
class InquiryAnalysisControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InquiryAnalysisService inquiryAnalysisService;

    @MockitoBean
    AnalysisLogService analysisLogService;

    @Test
    void analyzesInquiry() throws Exception {
        given(inquiryAnalysisService.analyze(1L)).willReturn(new InquiryAnalysisResponse(
                1L,
                InquiryStatus.AI_PROCESSED,
                false,
                true,
                false,
                new CategoryResultDto("REFUND", "refund request", true, false, false),
                new UrgencyResultDto("MEDIUM", "standard SLA"),
                List.of(new RetrievedManualChunkDto(
                        10L,
                        3L,
                        "환불 규정",
                        "REFUND",
                        0,
                        1,
                        12,
                        "환불은 영업일 기준 3일 내 처리됩니다."
                )),
                new DraftAnswerDto("안녕하세요. 환불 규정에 따라 ...", "정책 근거 확인 완료", List.of(10L))
        ));

        mockMvc.perform(post("/api/inquiries/1/analyze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inquiryId").value(1))
                .andExpect(jsonPath("$.category.value").value("REFUND"))
                .andExpect(jsonPath("$.urgency.value").value("MEDIUM"))
                .andExpect(jsonPath("$.retrievedChunks[0].id").value(10))
                .andExpect(jsonPath("$.draft.usedChunkIds[0]").value(10));
    }

    @Test
    void returnsRecentAnalysisLogs() throws Exception {
        given(analysisLogService.getRecentLogs(1L)).willReturn(List.of(new InquiryAnalysisLogResponse(
                10L,
                "SUCCESS",
                InquiryCategory.REFUND,
                UrgencyLevel.HIGH,
                "초안 답변",
                "gpt-test",
                "v1",
                321L,
                LocalDateTime.of(2026, 4, 8, 10, 3)
        )));

        mockMvc.perform(get("/api/inquiries/1/analysis-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].generatedDraft").value("초안 답변"));
    }

    @Test
    void ratesDraft() throws Exception {
        mockMvc.perform(post("/api/inquiries/1/rate-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":"BAD","reason":"WRONG_POLICY","note":"정책 버전이 다름"}
                                """))
                .andExpect(status().isOk());

        verify(analysisLogService).rateLatestLog(1L, "BAD", "WRONG_POLICY", "정책 버전이 다름");
    }

    @Test
    void rejectsUnknownRating() throws Exception {
        mockMvc.perform(post("/api/inquiries/1/rate-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":"MEH"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(analysisLogService, never()).rateLatestLog(anyLong(), any(), any(), any());
    }
}
