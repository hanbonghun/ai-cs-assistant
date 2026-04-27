package com.aicsassistant.analysis.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.order.InMemoryOrderRepository;
import org.junit.jupiter.api.Test;

class CheckOrderStatusToolTest {

    private final CheckOrderStatusTool tool = new CheckOrderStatusTool(new InMemoryOrderRepository());

    @Test
    void returnsSuccessForKnownOrderId() {
        ToolResult result = tool.execute(new CheckOrderStatusTool.Input("ORD-20260410-001"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).contains("주문번호: ORD-20260410-001");
        assertThat(result.errorCategory()).isNull();
    }

    @Test
    void returnsValidationErrorWhenOrderIdMissing() {
        ToolResult result = tool.execute(new CheckOrderStatusTool.Input(""));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.errorMessage()).contains("orderId");
    }

    @Test
    void returnsValidationErrorWhenOrderIdNull() {
        ToolResult result = tool.execute(new CheckOrderStatusTool.Input(null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
    }

    @Test
    void returnsNotFoundForUnknownOrderId() {
        ToolResult result = tool.execute(new CheckOrderStatusTool.Input("ORD-DOES-NOT-EXIST"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.NOT_FOUND);
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void exposesInputTypeForRuntimeDeserialization() {
        assertThat(tool.inputType()).isEqualTo(CheckOrderStatusTool.Input.class);
    }

    @Test
    void exposesAllSurfaceFieldsForLlm() {
        assertThat(tool.name()).isEqualTo("check_order_status");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.whenToUse()).isNotBlank();
        assertThat(tool.inputSchema()).contains("orderId");
        assertThat(tool.successOutputHint()).contains("주문번호").contains("결제금액");
        assertThat(tool.failureBehavior()).contains("NOT_FOUND");
    }

    @Test
    void usageBoundaryRedirectsToSiblingToolAndForbidsActions() {
        assertThat(tool.usageBoundary())
                .contains("Do NOT use")
                .contains("search_manual")
                .contains("read-only");
    }
}
