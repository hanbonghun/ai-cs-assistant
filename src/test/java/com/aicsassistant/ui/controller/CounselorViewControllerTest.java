package com.aicsassistant.ui.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aicsassistant.analysis.application.AnalysisLogService;
import com.aicsassistant.inquiry.application.InquiryService;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.ui.application.DashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CounselorViewController.class)
public class CounselorViewControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InquiryService inquiryService;

    @MockitoBean
    ManualService manualService;

    @MockitoBean
    AnalysisLogService analysisLogService;

    @MockitoBean
    DashboardService dashboardService;

    @MockitoBean
    ObjectMapper objectMapper;

    @Test
    public void rendersInquiryDetailPage() throws Exception {
        given(inquiryService.getInquiry(1L)).willReturn(new InquiryDetailResponse(
                1L,
                "cust-1",
                "문의 제목",
                "문의 내용",
                InquiryCategory.REFUND,
                UrgencyLevel.HIGH,
                InquiryStatus.NEW,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 4, 8, 9, 0),
                LocalDateTime.of(2026, 4, 8, 9, 0),
                List.of()
        ));

        mvc.perform(get("/ui/inquiries/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("inquiries/detail"));
    }
}
