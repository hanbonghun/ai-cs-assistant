package com.aicsassistant.inquiry.domain;

public enum InquiryStatus {
    NEW,
    PENDING_CUSTOMER,   // AI가 추가 정보 요청 중, 고객 답변 대기
    AI_PROCESSED,
    AUTO_ANSWERED,
    REVIEWED,
    CLOSED;

    /**
     * 답변이 확정되어 더 이상 분석 결과를 반영하지 않는 상태.
     *
     * <p>{@link Inquiry#applyAnalysis} 의 거부 조건과 분석 유스케이스의 진입·저장 검증이 같은 규칙을
     * 본다. 양쪽에 상태 목록을 따로 나열하면 상태가 하나 늘 때 한쪽만 고쳐지고, 그 어긋남은
     * "종료된 문의를 뒤늦은 분석 결과가 덮어쓰는" 형태로 조용히 드러난다.
     */
    public boolean isFinished() {
        return this == AUTO_ANSWERED || this == REVIEWED || this == CLOSED;
    }
}
