package com.aicsassistant.analysis.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.order.InMemoryOrderRepository;
import org.junit.jupiter.api.Test;

class CheckOrderStatusToolTest {

    /** ORD-20260410-001 은 cust-001 소유, ORD-20260401-004 는 cust-002 소유. */
    private final CheckOrderStatusTool tool =
            new CheckOrderStatusTool(new InMemoryOrderRepository(), "cust-001");

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
    void returnsNotFoundForOrderOwnedByAnotherCustomer() {
        ToolResult result = tool.execute(new CheckOrderStatusTool.Input("ORD-20260401-004"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.NOT_FOUND);
        // 존재하지 않는 주문과 동일한 응답 — 주문번호 스캐닝으로 타인의 주문 존재를 알아낼 수 없다
        assertThat(result.data()).isNull();
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
