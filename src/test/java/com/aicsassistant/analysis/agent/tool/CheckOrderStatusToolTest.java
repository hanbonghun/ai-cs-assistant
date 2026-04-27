package com.aicsassistant.analysis.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class CheckOrderStatusToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CheckOrderStatusTool tool = new CheckOrderStatusTool(new InMemoryOrderRepository());

    @Test
    void returnsSuccessForKnownOrderId() {
        ToolResult result = tool.execute(input("ORD-20260410-001"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).contains("주문번호: ORD-20260410-001");
        assertThat(result.errorCategory()).isNull();
    }

    @Test
    void returnsValidationErrorWhenOrderIdMissing() {
        ToolResult result = tool.execute(input(""));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.errorMessage()).contains("orderId");
    }

    @Test
    void returnsNotFoundForUnknownOrderId() {
        ToolResult result = tool.execute(input("ORD-DOES-NOT-EXIST"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.NOT_FOUND);
        assertThat(result.isRetryable()).isFalse();
    }

    private ObjectNode input(String orderId) {
        return objectMapper.createObjectNode().put("orderId", orderId);
    }
}
