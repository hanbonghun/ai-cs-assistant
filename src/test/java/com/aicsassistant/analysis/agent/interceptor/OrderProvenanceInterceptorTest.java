package com.aicsassistant.analysis.agent.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class OrderProvenanceInterceptorTest {

    private final OrderProvenanceInterceptor interceptor = new OrderProvenanceInterceptor();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolCallContext ctx = new ToolCallContext(1L, "cust-001");

    private ObjectNode inputWithOrderId(String orderId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", orderId);
        return node;
    }

    @Test
    void recordsOrderIdOnSuccessfulLookup() {
        interceptor.afterExecute("check_order_status", inputWithOrderId("ORD-20260410-001"),
                ToolResult.success("주문번호: ORD-20260410-001"), ctx);

        assertThat(ctx.hasObservedOrder("ORD-20260410-001")).isTrue();
    }

    @Test
    void doesNotRecordOnFailedLookup() {
        interceptor.afterExecute("check_order_status", inputWithOrderId("ORD-UNKNOWN"),
                ToolResult.error(ToolErrorCategory.NOT_FOUND, false, "없음"), ctx);

        assertThat(ctx.hasObservedOrder("ORD-UNKNOWN")).isFalse();
    }

    @Test
    void ignoresOtherTools() {
        interceptor.afterExecute("search_manual", inputWithOrderId("ORD-20260410-001"),
                ToolResult.success("정책 본문"), ctx);

        assertThat(ctx.hasObservedOrder("ORD-20260410-001")).isFalse();
    }

    @Test
    void returnsResultUnchanged() {
        ToolResult original = ToolResult.success("주문번호: ORD-20260410-001");

        ToolResult returned = interceptor.afterExecute(
                "check_order_status", inputWithOrderId("ORD-20260410-001"), original, ctx);

        assertThat(returned).isSameAs(original);
    }
}
