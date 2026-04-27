package com.aicsassistant.analysis.agent.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class HighValueOrderInterceptorTest {

    private final HighValueOrderInterceptor interceptor = new HighValueOrderInterceptor();
    private final ObjectNode emptyInput = new ObjectMapper().createObjectNode();
    private final ToolCallContext ctx = new ToolCallContext(1L);

    @Test
    void appendsGuardNoteForHighValueOrder() {
        String orderInfo = """
                주문번호: ORD-20260101-001
                상품명: 노트북
                상태: 결제완료
                결제금액: 1,500,000원
                """;
        ToolResult original = ToolResult.success(orderInfo);

        ToolResult result = interceptor.afterExecute("check_order_status", emptyInput, original, ctx);

        assertThat(result.ok()).isTrue();
        assertThat(result.data())
                .startsWith(orderInfo)
                .contains("[정책 가드:")
                .contains("1,500,000원")
                .contains("needsHumanReview: true")
                .contains("needsEscalation: true");
    }

    @Test
    void doesNotModifyLowValueOrder() {
        String orderInfo = "결제금액: 89,000원\n";
        ToolResult original = ToolResult.success(orderInfo);

        ToolResult result = interceptor.afterExecute("check_order_status", emptyInput, original, ctx);

        assertThat(result).isSameAs(original);
    }

    @Test
    void ignoresOtherTools() {
        String text = "결제금액: 5,000,000원\n";
        ToolResult original = ToolResult.success(text);

        ToolResult result = interceptor.afterExecute("search_manual", emptyInput, original, ctx);

        assertThat(result).isSameAs(original);
    }

    @Test
    void ignoresErrorResults() {
        ToolResult original = ToolResult.error(
                com.aicsassistant.analysis.agent.ToolErrorCategory.NOT_FOUND, false, "not found");

        ToolResult result = interceptor.afterExecute("check_order_status", emptyInput, original, ctx);

        assertThat(result).isSameAs(original);
    }

    @Test
    void ignoresResultWithoutAmountField() {
        ToolResult original = ToolResult.success("주문번호: ORD-X\n상태: 결제완료\n");

        ToolResult result = interceptor.afterExecute("check_order_status", emptyInput, original, ctx);

        assertThat(result).isSameAs(original);
    }
}
