package com.aicsassistant.analysis.agent.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.order.application.OrderService;
import com.aicsassistant.order.dto.OrderInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HighValueOrderInterceptorTest {

    @Mock
    OrderService orderService;

    @InjectMocks
    HighValueOrderInterceptor interceptor;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolCallContext ctx = new ToolCallContext(1L, "cust-001");

    private ObjectNode inputWithOrderId(String orderId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", orderId);
        return node;
    }

    @Test
    void appendsGuardNoteForHighValueOrder() {
        when(orderService.findOrder("ORD-HIGH", "cust-001")).thenReturn(Optional.of(
                new OrderInfo("ORD-HIGH", "노트북", "결제완료", 1_500_000, "2026-01-01", null, null, null, null)));
        ToolResult original = ToolResult.success("주문번호: ORD-HIGH\n");

        ToolResult result = interceptor.afterExecute("check_order_status", inputWithOrderId("ORD-HIGH"), original, ctx);

        assertThat(result.ok()).isTrue();
        assertThat(result.data())
                .startsWith("주문번호: ORD-HIGH")
                .contains("[정책 가드:")
                .contains("1,500,000원")
                .contains("needsHumanReview: true")
                .contains("needsEscalation: true");
    }

    @Test
    void doesNotModifyLowValueOrder() {
        when(orderService.findOrder("ORD-LOW", "cust-001")).thenReturn(Optional.of(
                new OrderInfo("ORD-LOW", "이어폰", "배송완료", 89_000, "2026-01-01", null, null, null, null)));
        ToolResult original = ToolResult.success("주문번호: ORD-LOW\n");

        ToolResult result = interceptor.afterExecute("check_order_status", inputWithOrderId("ORD-LOW"), original, ctx);

        assertThat(result).isSameAs(original);
    }

    @Test
    void ignoresOtherTools() {
        ToolResult original = ToolResult.success("policy text");

        ToolResult result = interceptor.afterExecute("search_manual", inputWithOrderId("ORD-X"), original, ctx);

        assertThat(result).isSameAs(original);
    }

    @Test
    void ignoresErrorResults() {
        ToolResult original = ToolResult.error(ToolErrorCategory.NOT_FOUND, false, "not found");

        ToolResult result = interceptor.afterExecute("check_order_status", inputWithOrderId("ORD-X"), original, ctx);

        assertThat(result).isSameAs(original);
    }

    @Test
    void ignoresWhenOrderIdMissing() {
        ToolResult original = ToolResult.success("주문번호 없음");

        ToolResult result = interceptor.afterExecute("check_order_status", mapper.createObjectNode(), original, ctx);

        assertThat(result).isSameAs(original);
    }

    @Test
    void ignoresWhenOrderNotFound() {
        when(orderService.findOrder("ORD-UNKNOWN", "cust-001")).thenReturn(Optional.empty());
        ToolResult original = ToolResult.success("어쩌고");

        ToolResult result = interceptor.afterExecute("check_order_status", inputWithOrderId("ORD-UNKNOWN"), original, ctx);

        assertThat(result).isSameAs(original);
    }
}
