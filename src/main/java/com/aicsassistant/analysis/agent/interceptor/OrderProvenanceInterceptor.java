package com.aicsassistant.analysis.agent.interceptor;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolCallInterceptor;
import com.aicsassistant.analysis.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 조회에 성공한 주문 ID를 컨텍스트에 기록한다.
 *
 * <p>{@code stage_refund}의 provenance 가드레일이 이 기록을 근거로 쓴다 — 모델이 주문번호를
 * 만들어내 환불을 제안하는 것을 막는다. 결과는 변형하지 않는다.
 */
@Component
public class OrderProvenanceInterceptor implements ToolCallInterceptor {

    private static final String TARGET_TOOL = "check_order_status";

    @Override
    public ToolResult afterExecute(String toolName, JsonNode input, ToolResult result, ToolCallContext ctx) {
        if (!TARGET_TOOL.equals(toolName) || !result.ok()) {
            return result;
        }
        String orderId = input.path("orderId").asText("").strip();
        if (!orderId.isBlank()) {
            ctx.recordObservedOrder(orderId);
        }
        return result;
    }
}
