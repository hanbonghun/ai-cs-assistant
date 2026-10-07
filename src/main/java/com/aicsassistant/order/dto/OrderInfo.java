package com.aicsassistant.order.dto;

/** 조회 시점에 조립된 주문 정보. 날짜는 이미 문자열로 포맷되어 있다. */
public record OrderInfo(
        String orderId,
        String productName,
        String status,
        int amount,
        String orderedAt,
        String courier,
        String trackingNumber,
        String estimatedDelivery,
        String note
) {
    /** 에이전트에게 넘기는 텍스트 형태 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("주문번호: ").append(orderId).append("\n");
        sb.append("상품명: ").append(productName).append("\n");
        sb.append("상태: ").append(status).append("\n");
        sb.append("결제금액: ").append(String.format("%,d", amount)).append("원\n");
        sb.append("주문일: ").append(orderedAt).append("\n");
        if (courier != null)           sb.append("배송사: ").append(courier).append("\n");
        if (trackingNumber != null)    sb.append("운송장번호: ").append(trackingNumber).append("\n");
        if (estimatedDelivery != null) sb.append("도착예정: ").append(estimatedDelivery).append("\n");
        if (note != null)              sb.append("비고: ").append(note).append("\n");
        return sb.toString();
    }
}
