package com.aicsassistant.analysis.agent;

/**
 * 한 번의 에이전트 실행({@link InquiryAgentService#run}) 동안 인터셉터들이 공유하는 가변 컨텍스트.
 *
 * <p>스레드 안전하지 않다. 에이전트 루프는 단일 스레드에서 실행되므로 동기화하지 않는다.
 */
public final class ToolCallContext {

    private final Long inquiryId;
    private final String customerIdentifier;
    private int toolCallCount;

    public ToolCallContext(Long inquiryId, String customerIdentifier) {
        this.inquiryId = inquiryId;
        this.customerIdentifier = customerIdentifier;
    }

    public Long inquiryId() {
        return inquiryId;
    }

    public String customerIdentifier() {
        return customerIdentifier;
    }

    public int toolCallCount() {
        return toolCallCount;
    }

    public void incrementToolCallCount() {
        toolCallCount++;
    }
}
