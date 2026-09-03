package com.aicsassistant.analysis.agent.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.aicsassistant.order.InMemoryOrderRepository.OrderInfo;
import com.aicsassistant.staging.domain.StagedChangeStatus;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundGuardrailInterceptorTest {

    @Mock
    InMemoryOrderRepository orderRepository;

    @Mock
    StagedChangeRepository stagedChangeRepository;

    @InjectMocks
    RefundGuardrailInterceptor interceptor;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolCallContext ctx = new ToolCallContext(1L, "cust-001");

    private ObjectNode input(String orderId, int amount) {
        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", orderId);
        node.put("amount", amount);
        return node;
    }

    private OrderInfo order(String status, int amount) {
        return new OrderInfo("ORD-A", "상품", status, amount, "2026-04-05", null, null, null, null);
    }

    private void givenObservedOrder(String status, int amount) {
        ctx.recordObservedOrder("ORD-A");
        lenient().when(orderRepository.findById("ORD-A", "cust-001"))
                .thenReturn(Optional.of(order(status, amount)));
    }

    @Test
    void allowsWhenAllGuardrailsPass() {
        givenObservedOrder("배송완료", 45_000);
        when(stagedChangeRepository.existsByOrderIdAndStatus("ORD-A", StagedChangeStatus.PENDING))
                .thenReturn(false);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 45_000), ctx);

        assertThat(blocked).isEmpty();
    }

    @Test
    void blocksOrderNotObservedInThisRun() {
        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 45_000), ctx);

        assertThat(blocked).isPresent();
        assertThat(blocked.get().errorCategory()).isEqualTo(ToolErrorCategory.PERMISSION);
        assertThat(blocked.get().isRetryable()).isFalse();
        assertThat(blocked.get().errorMessage()).contains("check_order_status");
    }

    @Test
    void blocksAmountAboveOrderTotal() {
        givenObservedOrder("배송완료", 45_000);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 450_000), ctx);

        assertThat(blocked).isPresent();
        assertThat(blocked.get().errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(blocked.get().errorMessage()).contains("45,000");
    }

    @Test
    void blocksAlreadyCancelledOrder() {
        givenObservedOrder("취소완료", 45_000);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 45_000), ctx);

        assertThat(blocked).isPresent();
        assertThat(blocked.get().errorCategory()).isEqualTo(ToolErrorCategory.PERMISSION);
        assertThat(blocked.get().errorMessage()).contains("취소완료");
    }

    @Test
    void blocksDuplicatePendingProposal() {
        givenObservedOrder("배송완료", 45_000);
        when(stagedChangeRepository.existsByOrderIdAndStatus("ORD-A", StagedChangeStatus.PENDING))
                .thenReturn(true);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 45_000), ctx);

        assertThat(blocked).isPresent();
        assertThat(blocked.get().errorCategory()).isEqualTo(ToolErrorCategory.PERMISSION);
        assertThat(blocked.get().errorMessage()).contains("대기");
    }

    @Test
    void blocksAlreadyRefundedOrder() {
        // ALREADY_REFUNDED_STATUS(환불완료)가 REFUND_BLOCKING_STATUSES 에도 있어야 한다 — 제안
        // 시점에 이 상태를 놓치면, 승인 시점에만 걸리는 죽은 카드(거부로만 해소되는)가 생긴다.
        givenObservedOrder("환불완료", 45_000);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 45_000), ctx);

        assertThat(blocked).isPresent();
        assertThat(blocked.get().errorCategory()).isEqualTo(ToolErrorCategory.PERMISSION);
        assertThat(blocked.get().errorMessage()).contains("환불완료");
    }

    @Test
    void allowsPartiallyRefundedOrder() {
        givenObservedOrder("부분환불완료", 215_000);
        when(stagedChangeRepository.existsByOrderIdAndStatus("ORD-A", StagedChangeStatus.PENDING))
                .thenReturn(false);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 32_000), ctx);

        assertThat(blocked).isEmpty();
    }

    @Test
    void ignoresOtherTools() {
        Optional<ToolResult> blocked = interceptor.beforeExecute("search_manual", input("ORD-A", 1), ctx);

        assertThat(blocked).isEmpty();
    }

    @Test
    void marksStagedChangeOnSuccess() {
        interceptor.afterExecute("stage_refund", input("ORD-A", 45_000),
                ToolResult.success("환불 제안 #1 접수됨"), ctx);

        assertThat(ctx.stagedChange()).isTrue();
    }

    @Test
    void doesNotMarkStagedChangeOnFailure() {
        interceptor.afterExecute("stage_refund", input("ORD-A", 45_000),
                ToolResult.error(ToolErrorCategory.VALIDATION, false, "잘못된 금액"), ctx);

        assertThat(ctx.stagedChange()).isFalse();
    }
}
