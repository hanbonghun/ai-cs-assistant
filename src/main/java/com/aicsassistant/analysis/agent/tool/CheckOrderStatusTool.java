package com.aicsassistant.analysis.agent.tool;

import com.aicsassistant.analysis.agent.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Mock tool simulating an order management system lookup.
 *
 * Known order IDs return rich, scenario-specific data useful for demos.
 * Unknown IDs fall back to deterministic generation based on the ID hash.
 *
 * In production this would call an internal order service API or
 * query a local read-model table synced from the order domain.
 */
public class CheckOrderStatusTool implements AgentTool {

    private record OrderInfo(
            String status,
            String productName,
            int amount,
            String orderedAt,
            String courier,
            String trackingNumber,
            String estimatedDelivery,
            String note
    ) {}

    // 시나리오별 대표 주문 데이터
    private static final Map<String, OrderInfo> ORDERS = Map.ofEntries(

        // 배송 지연 — 취소 요청 시나리오
        Map.entry("ORD-20260410-001", new OrderInfo(
                "배송중",
                "무선 블루투스 이어폰 AX-300",
                89_000,
                "2026-04-10",
                "CJ대한통운",
                "375234567890",
                "2026-04-13",
                "인천 물류센터 경유 중. 예상 도착일보다 1일 지연"
        )),

        // 배송 완료 후 반품 요청 시나리오
        Map.entry("ORD-20260405-002", new OrderInfo(
                "배송완료",
                "코튼 후드티 (네이비, XL)",
                45_000,
                "2026-04-05",
                "한진택배",
                "123456789012",
                "2026-04-08",
                "2026-04-08 14:32 배송 완료. 반품 가능 기간: ~2026-04-15"
        )),

        // 결제 직후 취소 가능 시나리오
        Map.entry("ORD-20260412-003", new OrderInfo(
                "결제완료",
                "유기농 그래놀라 500g × 3개",
                38_700,
                "2026-04-12",
                null,
                null,
                null,
                "아직 출고 전. 즉시 취소 가능"
        )),

        // 취소 처리 중 — 환불 대기 시나리오
        Map.entry("ORD-20260401-004", new OrderInfo(
                "취소처리중",
                "스탠드 조명 LD-500",
                128_000,
                "2026-04-01",
                null,
                null,
                null,
                "고객 요청으로 2026-04-03 취소 접수. 환불 처리 중 (영업일 2~3일 소요)"
        )),

        // 교환 진행 중 시나리오
        Map.entry("ORD-20260330-005", new OrderInfo(
                "교환진행중",
                "러닝화 TR-200 (270mm)",
                112_000,
                "2026-03-30",
                "로젠택배",
                "654321098765",
                "2026-04-14",
                "사이즈 교환 요청 접수. 수거 완료 후 재발송 예정"
        )),

        // 부분 환불 완료 시나리오
        Map.entry("ORD-20260320-006", new OrderInfo(
                "부분환불완료",
                "주방용품 세트 (4종) 외 2건",
                215_000,
                "2026-03-20",
                "CJ대한통운",
                "987001234567",
                "2026-03-25",
                "총 3건 중 1건(냄비 뚜껑 파손) 부분 환불 32,000원 완료. 나머지 정상 배송"
        )),

        // 중복 결제 의심 시나리오
        Map.entry("ORD-20260411-007", new OrderInfo(
                "결제완료",
                "보습 크림 세트",
                56_000,
                "2026-04-11",
                null,
                null,
                null,
                "동일 상품 동일 시각 2건 결제 감지. 이상거래 검토 중"
        )),

        // 오배송 시나리오
        Map.entry("ORD-20260407-008", new OrderInfo(
                "배송완료",
                "요가 매트 6mm (퍼플)",
                35_000,
                "2026-04-07",
                "한진택배",
                "112233445566",
                "2026-04-09",
                "2026-04-09 배송 완료. 고객 수령 상품이 주문 상품과 다를 수 있음 (오배송 신고 접수됨)"
        )),

        // 정상 배송 완료 (단순 조회)
        Map.entry("ORD-20260325-009", new OrderInfo(
                "배송완료",
                "애플 워치 밴드 (44mm, 블랙)",
                29_000,
                "2026-03-25",
                "CJ대한통운",
                "223344556677",
                "2026-03-27",
                "2026-03-27 11:20 배송 완료"
        )),
        Map.entry("ORD-20260301-010", new OrderInfo(
                "배송완료",
                "기계식 키보드 TK-65",
                145_000,
                "2026-03-01",
                "한진택배",
                "334455667788",
                "2026-03-04",
                "2026-03-04 14:05 배송 완료"
        )),

        // 고객 취소 완료
        Map.entry("ORD-20260218-011", new OrderInfo(
                "취소완료",
                "텀블러 500ml (매트 그레이)",
                32_000,
                "2026-02-18",
                null,
                null,
                null,
                "2026-02-18 고객 요청으로 즉시 취소. 환불 완료 (카드 취소 2~3 영업일 소요)"
        )),

        // 출고 대기 중
        Map.entry("ORD-20260411-012", new OrderInfo(
                "결제완료",
                "필라테스 레깅스 (스몰, 네이비)",
                67_000,
                "2026-04-11",
                null,
                null,
                null,
                "결제 확인 완료. 출고 준비 중 (1~2 영업일 내 발송 예정)"
        )),
        Map.entry("ORD-20260308-013", new OrderInfo(
                "배송완료",
                "아로마 디퓨저 세트",
                54_000,
                "2026-03-08",
                "로젠택배",
                "445566778899",
                "2026-03-11",
                "2026-03-11 10:30 배송 완료"
        )),
        Map.entry("ORD-20260222-014", new OrderInfo(
                "배송완료",
                "접이식 요가 블록 (2개 세트)",
                18_000,
                "2026-02-22",
                "우체국택배",
                "556677889900",
                "2026-02-24",
                "2026-02-24 배송 완료"
        )),

        // 배송 중
        Map.entry("ORD-20260409-015", new OrderInfo(
                "배송중",
                "노트북 파우치 15인치 (브라운)",
                42_000,
                "2026-04-09",
                "CJ대한통운",
                "667788990011",
                "2026-04-12",
                "2026-04-11 발송. 도착 예정일 내 배송 정상 진행 중"
        )),
        Map.entry("ORD-20260315-016", new OrderInfo(
                "배송완료",
                "무선 충전 패드 (고속)",
                38_000,
                "2026-03-15",
                "한진택배",
                "778899001122",
                "2026-03-17",
                "2026-03-17 배송 완료"
        )),

        // 반품 완료
        Map.entry("ORD-20260228-017", new OrderInfo(
                "반품완료",
                "미니 가습기 (화이트)",
                25_000,
                "2026-02-28",
                "CJ대한통운",
                "889900112233",
                "2026-03-02",
                "제품 불량으로 반품 접수 후 처리 완료. 환불 완료"
        ))
    );

    private static final String[] FALLBACK_STATUSES = {
            "배송중", "배송완료", "결제완료", "배송준비중", "취소처리중"
    };
    private static final String[] FALLBACK_PRODUCTS = {
            "생활용품 세트", "의류 상품", "전자기기 액세서리", "식품류", "뷰티 제품"
    };
    private static final String[] FALLBACK_COURIERS = {
            "CJ대한통운", "한진택배", "로젠택배", "우체국택배"
    };

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

        OrderInfo order = ORDERS.get(orderId);
        if (order != null) {
            return format(orderId, order);
        }
        return fallback(orderId);
    }

    private String format(String orderId, OrderInfo o) {
        StringBuilder sb = new StringBuilder();
        sb.append("주문번호: ").append(orderId).append("\n");
        sb.append("상품명: ").append(o.productName()).append("\n");
        sb.append("상태: ").append(o.status()).append("\n");
        sb.append("결제금액: ").append(String.format("%,d", o.amount())).append("원\n");
        sb.append("주문일: ").append(o.orderedAt()).append("\n");
        if (o.courier() != null) {
            sb.append("배송사: ").append(o.courier()).append("\n");
        }
        if (o.trackingNumber() != null) {
            sb.append("운송장번호: ").append(o.trackingNumber()).append("\n");
        }
        if (o.estimatedDelivery() != null) {
            sb.append("도착예정: ").append(o.estimatedDelivery()).append("\n");
        }
        if (o.note() != null) {
            sb.append("비고: ").append(o.note()).append("\n");
        }
        return sb.toString();
    }

    private String fallback(String orderId) {
        int hash = Math.abs(orderId.hashCode());
        String status = FALLBACK_STATUSES[hash % FALLBACK_STATUSES.length];
        String product = FALLBACK_PRODUCTS[(hash / 7) % FALLBACK_PRODUCTS.length];
        String courier = FALLBACK_COURIERS[(hash / 13) % FALLBACK_COURIERS.length];
        int amount = 15_000 + (hash % 185_000);
        int day = 1 + (hash % 28);

        return """
                주문번호: %s
                상품명: %s
                상태: %s
                결제금액: %,d원
                주문일: 2026-04-%02d
                배송사: %s
                """.formatted(orderId, product, status, amount, day, courier);
    }
}
