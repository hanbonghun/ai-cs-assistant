package com.aicsassistant.analysis.agent.interceptor;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolCallInterceptor;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.aicsassistant.order.InMemoryOrderRepository.OrderInfo;
import static com.aicsassistant.staging.domain.RefundGuardrails.REFUND_BLOCKING_STATUSES;

import com.aicsassistant.staging.domain.StagedChangeStatus;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code stage_refund} 호출을 실행 전에 검사한다.
 *
 * <p>가드레일 4종을 툴 밖에 두는 이유: 툴은 {@code execute(Input)} 만 받아
 * {@link ToolCallContext}(provenance)를 볼 수 없다. 프롬프트 지시가 아니라 코드로 막는다.
 *
 * <p>{@code afterExecute}에서 제안 접수를 컨텍스트에 표시해 finalAnswer 의 상담사 검토를 강제한다 —
 * 가드레일의 마지막 단계다.
 */
@Component
@RequiredArgsConstructor
public class RefundGuardrailInterceptor implements ToolCallInterceptor {

    private static final String TARGET_TOOL = "stage_refund";

    private final InMemoryOrderRepository orderRepository;
    private final StagedChangeRepository stagedChangeRepository;

    @Override
    public Optional<ToolResult> beforeExecute(String toolName, JsonNode input, ToolCallContext ctx) {
        if (!TARGET_TOOL.equals(toolName)) {
            return Optional.empty();
        }

        String orderId = input.path("orderId").asText("").strip();
        int amount = input.path("amount").asInt(0);

        // (1) provenance — 모델이 주문번호를 만들어내는 것을 막는다
        if (!ctx.hasObservedOrder(orderId)) {
            return blocked(ToolErrorCategory.PERMISSION,
                    "주문 [" + orderId + "]은 이번 대화에서 조회되지 않았습니다. "
                            + "환불을 제안하기 전에 check_order_status로 주문을 먼저 조회하세요.");
        }

        OrderInfo order = orderRepository.findById(orderId, ctx.customerIdentifier()).orElse(null);
        if (order == null) {
            return blocked(ToolErrorCategory.PERMISSION,
                    "주문 [" + orderId + "] 정보를 확인할 수 없습니다. finalAnswer에서 needsHumanReview: true로 설정하세요.");
        }

        // (2) 주문 상태
        if (REFUND_BLOCKING_STATUSES.contains(order.status())) {
            return blocked(ToolErrorCategory.PERMISSION,
                    "주문 상태가 [" + order.status() + "]로 이미 환불이 완료되었거나 진행 중입니다. "
                            + "환불을 다시 제안하지 말고 finalAnswer에서 needsHumanReview: true로 설정하세요.");
        }

        // (3) 금액
        if (amount > order.amount()) {
            return blocked(ToolErrorCategory.VALIDATION,
                    "제안 금액 %,d원이 결제금액 %,d원을 초과합니다. 결제금액 이하로 수정해 다시 시도하세요."
                            .formatted(amount, order.amount()));
        }

        // (4) 중복
        if (stagedChangeRepository.existsByOrderIdAndStatus(orderId, StagedChangeStatus.PENDING)) {
            return blocked(ToolErrorCategory.PERMISSION,
                    "주문 [" + orderId + "]에는 이미 승인 대기 중인 환불 제안이 있습니다. "
                            + "중복 제안하지 말고 finalAnswer에서 needsHumanReview: true로 설정하세요.");
        }

        return Optional.empty();
    }

    @Override
    public ToolResult afterExecute(String toolName, JsonNode input, ToolResult result, ToolCallContext ctx) {
        if (TARGET_TOOL.equals(toolName) && result.ok()) {
            ctx.markStagedChange();
        }
        return result;
    }

    private Optional<ToolResult> blocked(ToolErrorCategory category, String message) {
        return Optional.of(ToolResult.error(category, false, message));
    }
}
