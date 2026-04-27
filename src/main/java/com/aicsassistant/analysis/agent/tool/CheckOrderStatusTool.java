package com.aicsassistant.analysis.agent.tool;

import com.aicsassistant.analysis.agent.AgentTool;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.order.InMemoryOrderRepository;

/**
 * 주문 조회 도구 — InMemoryOrderRepository에 위임하는 얇은 어댑터.
 * 실제 서비스에서는 주문 도메인 API 클라이언트로 교체된다.
 */
public class CheckOrderStatusTool implements AgentTool<CheckOrderStatusTool.Input> {

    /** 도구 입력 — 조회할 주문 식별자. */
    public record Input(String orderId) {}

    private final InMemoryOrderRepository orderRepository;

    public CheckOrderStatusTool(InMemoryOrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public String name() {
        return "check_order_status";
    }

    @Override
    public String description() {
        return "Looks up the current status, tracking, and amount of a specific order by its ID.";
    }

    @Override
    public String whenToUse() {
        return "Call when the customer mentions or references a specific order ID (e.g. 'ORD-20260410-001'). "
                + "If the customer talks about an order without giving an ID, use followUpQuestion to ask first — do not guess.";
    }

    @Override
    public String usageBoundary() {
        return "Do NOT use for: (1) general policy questions (use search_faq for short FAQs, search_manual for detailed policy), "
                + "(2) questions about orders the customer has not identified by ID, "
                + "(3) performing actions like cancellation/refund (this tool is read-only — for actions, set needsHumanReview: true).";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public String inputSchema() {
        return "{\"orderId\": \"string (required) — full order identifier as provided by the customer (e.g. 'ORD-20260410-001')\"}";
    }

    @Override
    public String successOutputHint() {
        return "Multi-line text with fields '주문번호', '상품명', '상태', '결제금액', '주문일' "
                + "and optional '배송사', '운송장번호', '도착예정', '비고'. May also contain '[정책 가드: ...]' note for high-value orders (treat as a hard rule).";
    }

    @Override
    public String failureBehavior() {
        return "VALIDATION (empty orderId): ask the customer for the order ID via followUpQuestion. "
                + "NOT_FOUND: ask the customer to confirm the order ID via followUpQuestion (the customer may have mistyped). "
                + "Do not retry the same input.";
    }

    @Override
    public ToolResult execute(Input input) {
        String orderId = input.orderId() == null ? "" : input.orderId().strip();
        if (orderId.isBlank()) {
            return ToolResult.error(
                    ToolErrorCategory.VALIDATION,
                    false,
                    "'orderId' field is required.");
        }
        return orderRepository.findById(orderId)
                .map(o -> ToolResult.success(orderRepository.formatText(o)))
                .orElseGet(() -> ToolResult.error(
                        ToolErrorCategory.NOT_FOUND,
                        false,
                        "주문번호 [" + orderId + "]에 해당하는 주문 정보를 찾을 수 없습니다. "
                                + "고객에게 주문번호를 다시 확인해달라고 followUpQuestion으로 요청하세요."));
    }
}
