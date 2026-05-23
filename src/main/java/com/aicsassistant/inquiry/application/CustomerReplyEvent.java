package com.aicsassistant.inquiry.application;

/**
 * 고객이 AI의 추가 질문(PENDING_CUSTOMER)에 답변을 달았을 때 발행되는 이벤트.
 *
 * <p>이벤트 리스너에서 비동기로 agent를 재실행한다. HTTP 요청은 이벤트 발행 후
 * 즉시 202 Accepted로 응답하고, 클라이언트는 inquiry 상세 폴링으로 결과를 확인한다.
 */
public record CustomerReplyEvent(Long inquiryId) {
}
