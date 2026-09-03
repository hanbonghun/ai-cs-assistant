package com.aicsassistant.analysis.agent;

import java.util.HashSet;
import java.util.Set;

/**
 * 한 번의 에이전트 실행({@link InquiryAgentService#run}) 동안 인터셉터들이 공유하는 가변 컨텍스트.
 *
 * <p>스레드 안전하지 않다. 에이전트 루프는 단일 스레드에서 실행되므로 동기화하지 않는다.
 */
public final class ToolCallContext {

    private final Long inquiryId;
    private final String customerIdentifier;
    private int toolCallCount;
    private final Set<String> observedOrderIds = new HashSet<>();
    private boolean stagedChange;

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

    /** 이번 실행에서 조회에 성공한 주문. 환불 제안의 provenance 근거가 된다. */
    public void recordObservedOrder(String orderId) {
        observedOrderIds.add(orderId);
    }

    public boolean hasObservedOrder(String orderId) {
        return observedOrderIds.contains(orderId);
    }

    /** 이번 실행에서 제안이 접수되었음을 표시한다. finalAnswer의 상담사 검토를 강제하는 데 쓴다. */
    public void markStagedChange() {
        stagedChange = true;
    }

    public boolean stagedChange() {
        return stagedChange;
    }
}
