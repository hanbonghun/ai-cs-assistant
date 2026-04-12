package com.aicsassistant.analysis.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicsassistant.analysis.application.InquiryAnalysisService;
import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.DraftAnswerDto;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.common.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InquiryAnalysisController.class)
@Import(GlobalExceptionHandler.class)
class InquiryAnalysisControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InquiryAnalysisService inquiryAnalysisService;

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
}
