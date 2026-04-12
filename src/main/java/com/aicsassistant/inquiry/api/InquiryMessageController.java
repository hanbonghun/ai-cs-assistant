package com.aicsassistant.inquiry.api;

import com.aicsassistant.analysis.application.InquiryAnalysisService;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inquiries")
@RequiredArgsConstructor
public class InquiryMessageController {

    private final InquiryRepository inquiryRepository;
    private final InquiryMessageRepository messageRepository;
    private final InquiryAnalysisService analysisService;

    record CustomerReplyRequest(String content) {}

    /**
     * 고객이 AI 추가 질문에 답변을 달면 에이전트가 대화 히스토리와 함께 재실행된다.
     */
    @PostMapping("/{id}/messages")
    public InquiryAnalysisResponse reply(
            @PathVariable Long id,
            @RequestBody CustomerReplyRequest request
    ) {
        var inquiry = inquiryRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Inquiry not found"));

        if (inquiry.getStatus() != InquiryStatus.PENDING_CUSTOMER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE",
                    "고객 답변은 PENDING_CUSTOMER 상태에서만 가능합니다.");
        }

        if (request.content() == null || request.content().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_CONTENT", "답변 내용을 입력해 주세요.");
        }

        // 고객 메시지 저장 후 에이전트 재실행
        messageRepository.save(InquiryMessage.of(id, InquiryMessageRole.CUSTOMER, request.content().strip()));
        return analysisService.analyze(id);
    }
}
