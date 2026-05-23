package com.aicsassistant.inquiry.api;

import com.aicsassistant.inquiry.application.InquiryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    record CustomerReplyRequest(String content) {}

    /**
     * 고객이 AI 추가 질문(PENDING_CUSTOMER)에 답변을 제출한다.
     *
     * <p>메시지 저장만 동기로 처리하고 즉시 {@code 202 Accepted}로 응답한다.
     * 이어지는 agent 재실행은 {@code CustomerReplyEvent} 리스너에서 비동기로 진행되며,
     * 클라이언트는 {@code GET /api/inquiries/{id}} 폴링으로 status 변화를 감지한다.
     */
    @PostMapping("/{id}/messages")
    public ResponseEntity<Void> reply(
            @PathVariable Long id,
            @RequestBody CustomerReplyRequest request
    ) {
        inquiryService.replyAsCustomer(id, request.content());
        return ResponseEntity.accepted().build();
    }
}
