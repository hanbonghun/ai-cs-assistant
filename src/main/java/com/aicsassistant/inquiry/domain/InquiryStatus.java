package com.aicsassistant.inquiry.domain;

public enum InquiryStatus {
    NEW,
    PENDING_CUSTOMER,   // AI가 추가 정보 요청 중, 고객 답변 대기
    AI_PROCESSED,
    AUTO_ANSWERED,
    REVIEWED,
    CLOSED
}
