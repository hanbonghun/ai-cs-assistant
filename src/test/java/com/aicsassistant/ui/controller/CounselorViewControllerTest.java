package com.aicsassistant.ui.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.common.exception.ViewExceptionHandler;
import com.aicsassistant.inquiry.application.InquiryService;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import com.aicsassistant.inquiry.dto.InquiryListResponse;
import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.staging.application.StagedChangeApprovalService;
import com.aicsassistant.staging.dto.StagedChangeResponse;
import com.aicsassistant.ui.application.DashboardService;
import com.aicsassistant.ui.application.InquiryDetailAssembler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CounselorViewController.class)
@Import(ViewExceptionHandler.class)
public class CounselorViewControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InquiryService inquiryService;

    @MockitoBean
    ManualService manualService;

    @MockitoBean
    InquiryDetailAssembler inquiryDetailAssembler;

    @MockitoBean
    DashboardService dashboardService;

    @MockitoBean
    StagedChangeApprovalService stagedChangeApprovalService;

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

    /**
     * detail.html의 환불 제안 카드·이력 블록은 Thymeleaf 표현식(th:each/th:if/#numbers/#temporals)이라
     * 컴파일이나 다른 계층 테스트로는 검증되지 않는다 — MockMvc가 실제로 뷰 리졸버를 태워 렌더링해야만
     * 표현식 오류가 드러난다. stagedChangeApprovalService가 빈 리스트를 반환하면 두 블록 모두
     * th:each가 스킵되어 새 표현식이 하나도 평가되지 않으므로, 여기서는 PENDING/APPROVED/REJECTED
     * 3건을 채워 넣어 모든 분기(policyBasis 유무, approvedAmount 유무, decisionNote 유무)를 최소 1회씩
     * 실제로 평가시킨다. 다른 뷰 컨트롤러 테스트처럼 우선순위가 낮지 않다 — 지우지 말 것.
     */
    @Test
    public void rendersStagedChangeProposalsAndHistory() throws Exception {
        given(inquiryService.getInquiry(1L)).willReturn(new InquiryDetailResponse(
                1L,
                "cust-1",
                "문의 제목",
                "문의 내용",
                InquiryCategory.REFUND,
                UrgencyLevel.HIGH,
                InquiryStatus.AI_PROCESSED,
                "AI 초안",
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 4, 8, 9, 0),
                LocalDateTime.of(2026, 4, 8, 9, 0),
                List.of()
        ));
        given(stagedChangeApprovalService.findByInquiry(1L)).willReturn(List.of(
                new StagedChangeResponse(1L, "REFUND", "ORD-1", 45000, null,
                        "고객이 미배송을 주장", "환불정책 3조", "PENDING",
                        null, null, null, LocalDateTime.of(2026, 4, 8, 9, 0)),
                new StagedChangeResponse(2L, "REFUND", "ORD-2", 50000, 32000,
                        "부분 파손 확인", null, "APPROVED",
                        "counselor-9", LocalDateTime.of(2026, 4, 8, 10, 15), "금액 확인 후 조정",
                        LocalDateTime.of(2026, 4, 8, 10, 0)),
                new StagedChangeResponse(3L, "REFUND", "ORD-3", 20000, null,
                        "단순 변심", null, "REJECTED",
                        "counselor-9", LocalDateTime.of(2026, 4, 8, 11, 0), "환불 요건 미충족",
                        LocalDateTime.of(2026, 4, 8, 10, 30))
        ));

        mvc.perform(get("/ui/inquiries/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("inquiries/detail"))
                .andExpect(content().string(containsString("ORD-1")))
                .andExpect(content().string(containsString("45,000원")))
                .andExpect(content().string(containsString("AI 제안 50,000원 → 승인 32,000원")));
    }

    @Test
    public void rendersInquiryListWhenCategoryOrUrgencyIsNull() throws Exception {
        // 이 케이스가 프로덕션에서 500 을 냈다. AI 분석 전이거나 분석이 실패한 문의는
        // category/urgency 가 null 인데, 템플릿이 .name() 을 무가드로 부르면 그 문의 하나가
        // 목록 전체를 죽인다. 렌더 테스트만 잡을 수 있는 종류라 여기 고정한다.
        given(inquiryService.getInquiries(null, null, null)).willReturn(List.of(
                new InquiryListResponse(1L, "cust-001", "분석 완료 문의",
                        InquiryCategory.REFUND, UrgencyLevel.HIGH, InquiryStatus.AI_PROCESSED,
                        LocalDateTime.of(2026, 4, 8, 9, 0), LocalDateTime.of(2026, 4, 8, 9, 0)),
                new InquiryListResponse(2L, "cust-002", "분석 전 문의",
                        null, null, InquiryStatus.NEW,
                        LocalDateTime.of(2026, 4, 8, 9, 0), LocalDateTime.of(2026, 4, 8, 9, 0))
        ));

        mvc.perform(get("/ui/inquiries"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("분석 완료 문의")))
                .andExpect(content().string(containsString("분석 전 문의")));
    }

    @Test
    public void missingInquiryRendersErrorPageWithNotFoundStatus() throws Exception {
        // GlobalExceptionHandler 를 REST 전용으로 좁힌 뒤 뷰의 ApiException 을 아무도 번역하지
        // 않아 상태가 전부 500 이 됐다. 없는 문의를 여는 것은 404 다 — 화면과 상태 코드가 함께 맞아야 한다.
        given(inquiryService.getInquiry(999_999L))
                .willThrow(new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Inquiry not found"));

        mvc.perform(get("/ui/inquiries/999999"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    public void rendersInquiryDetailWhenCategoryOrUrgencyIsNull() throws Exception {
        // 분석이 실패하면 category/urgency 가 null 로 남는다(실제로 DB 커넥션이 끊겨 그런 문의가 생겼다).
        // 목록과 같은 결함이 상세에도 있었는데, 앞선 전수 조사에서 grep 출력을 head 로 잘라 놓쳤다.
        given(inquiryService.getInquiry(2L)).willReturn(new InquiryDetailResponse(
                2L, "cust-001", "결제 문의", "이중 결제된거같음",
                null, null, InquiryStatus.NEW,
                null, null, null, null, null,
                LocalDateTime.of(2026, 9, 4, 4, 46), LocalDateTime.of(2026, 9, 4, 4, 46),
                List.of()
        ));

        mvc.perform(get("/ui/inquiries/2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("결제 문의")));
    }
}
