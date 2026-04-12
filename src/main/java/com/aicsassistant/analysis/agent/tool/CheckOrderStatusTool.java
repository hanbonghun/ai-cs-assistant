package com.aicsassistant.analysis.agent.tool;

import com.aicsassistant.analysis.agent.AgentTool;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 주문 조회 툴 — InMemoryOrderRepository에 위임하는 얇은 어댑터.
 *
 * 실제 서비스에서는 주문 도메인 API 클라이언트로 교체합니다.
 */
public class CheckOrderStatusTool implements AgentTool {

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
        return "check_order_status(orderId: string) — Looks up the current status of an order. "
                + "Use when the customer mentions a specific order ID.";
    }

    @Override
    public String execute(JsonNode input) {
        String orderId = input.path("orderId").asText("").strip();
        if (orderId.isBlank()) {
            return "Error: 'orderId' field is required.";
        }
        return orderRepository.findById(orderId)
                .map(orderRepository::formatText)
                .orElse("주문번호 [" + orderId + "]에 해당하는 주문 정보를 찾을 수 없습니다. 고객에게 주문번호를 다시 확인해달라고 안내하세요.");
    }
}
