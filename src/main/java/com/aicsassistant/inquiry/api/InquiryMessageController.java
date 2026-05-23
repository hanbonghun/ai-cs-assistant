package com.aicsassistant.inquiry.api;

import com.aicsassistant.analysis.application.InquiryAnalysisService;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.inquiry.application.InquiryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inquiries")
@RequiredArgsConstructor
public class InquiryMessageController {

    private final InquiryService inquiryService;
    private final InquiryAnalysisService analysisService;

    record CustomerReplyRequest(String content) {}

    /**
     * 고객이 AI 추가 질문에 답변을 달면 에이전트가 대화 히스토리와 함께 재실행된다.
     * 메시지 저장과 분석은 의도적으로 별도 트랜잭션 (LLM 호출 동안 DB 락을 잡지 않기 위함).
     */
    @PostMapping("/{id}/messages")
    public InquiryAnalysisResponse reply(
            @PathVariable Long id,
            @RequestBody CustomerReplyRequest request
    ) {
        inquiryService.replyAsCustomer(id, request.content());
        return analysisService.analyze(id);
    }
}
