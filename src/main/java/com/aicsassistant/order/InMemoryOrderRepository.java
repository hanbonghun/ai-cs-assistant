package com.aicsassistant.order;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 데모용 인메모리 주문 데이터 저장소.
 *
 * <p>실제 서비스에서는 주문 도메인 서비스 API 호출 또는
 * 주문 도메인에서 동기화된 로컬 read-model 테이블 조회로 대체합니다.
 */
@Repository
public class InMemoryOrderRepository {

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
    ) {}

    private static final Map<String, OrderInfo> ORDERS = Map.ofEntries(

        // 배송 지연 — 취소 요청 시나리오
        Map.entry("ORD-20260410-001", new OrderInfo(
                "ORD-20260410-001", "무선 블루투스 이어폰 AX-300", "배송중", 89_000, "2026-04-10",
                "CJ대한통운", "375234567890", "2026-04-13",
                "인천 물류센터 경유 중. 예상 도착일보다 1일 지연")),

        // 배송 완료 후 반품 요청 시나리오
        Map.entry("ORD-20260405-002", new OrderInfo(
                "ORD-20260405-002", "코튼 후드티 (네이비, XL)", "배송완료", 45_000, "2026-04-05",
                "한진택배", "123456789012", "2026-04-08",
                "2026-04-08 14:32 배송 완료. 반품 가능 기간: ~2026-04-15")),

        // 결제 직후 취소 가능 시나리오
        Map.entry("ORD-20260412-003", new OrderInfo(
                "ORD-20260412-003", "유기농 그래놀라 500g × 3개", "결제완료", 38_700, "2026-04-12",
                null, null, null,
                "아직 출고 전. 즉시 취소 가능")),

        // 취소 처리 중 — 환불 대기 시나리오
        Map.entry("ORD-20260401-004", new OrderInfo(
                "ORD-20260401-004", "스탠드 조명 LD-500", "취소처리중", 128_000, "2026-04-01",
                null, null, null,
                "고객 요청으로 2026-04-03 취소 접수. 환불 처리 중 (영업일 2~3일 소요)")),

        // 교환 진행 중 시나리오
        Map.entry("ORD-20260330-005", new OrderInfo(
                "ORD-20260330-005", "러닝화 TR-200 (270mm)", "교환진행중", 112_000, "2026-03-30",
                "로젠택배", "654321098765", "2026-04-14",
                "사이즈 교환 요청 접수. 수거 완료 후 재발송 예정")),

        // 부분 환불 완료 시나리오
        Map.entry("ORD-20260320-006", new OrderInfo(
                "ORD-20260320-006", "주방용품 세트 (4종) 외 2건", "부분환불완료", 215_000, "2026-03-20",
                "CJ대한통운", "987001234567", "2026-03-25",
                "총 3건 중 1건(냄비 뚜껑 파손) 부분 환불 32,000원 완료. 나머지 정상 배송")),

        // 중복 결제 의심 시나리오
        Map.entry("ORD-20260411-007", new OrderInfo(
                "ORD-20260411-007", "보습 크림 세트", "결제완료", 56_000, "2026-04-11",
                null, null, null,
                "동일 상품 동일 시각 2건 결제 감지. 이상거래 검토 중")),

        // 오배송 시나리오
        Map.entry("ORD-20260407-008", new OrderInfo(
                "ORD-20260407-008", "요가 매트 6mm (퍼플)", "배송완료", 35_000, "2026-04-07",
                "한진택배", "112233445566", "2026-04-09",
                "2026-04-09 배송 완료. 고객 수령 상품이 주문 상품과 다를 수 있음 (오배송 신고 접수됨)")),

        // 정상 배송 완료 (단순 조회)
        Map.entry("ORD-20260325-009", new OrderInfo(
                "ORD-20260325-009", "애플 워치 밴드 (44mm, 블랙)", "배송완료", 29_000, "2026-03-25",
                "CJ대한통운", "223344556677", "2026-03-27",
                "2026-03-27 11:20 배송 완료")),

        Map.entry("ORD-20260301-010", new OrderInfo(
                "ORD-20260301-010", "기계식 키보드 TK-65", "배송완료", 145_000, "2026-03-01",
                "한진택배", "334455667788", "2026-03-04",
                "2026-03-04 14:05 배송 완료")),

        // 고객 취소 완료
        Map.entry("ORD-20260218-011", new OrderInfo(
                "ORD-20260218-011", "텀블러 500ml (매트 그레이)", "취소완료", 32_000, "2026-02-18",
                null, null, null,
                "2026-02-18 고객 요청으로 즉시 취소. 환불 완료 (카드 취소 2~3 영업일 소요)")),

        // 출고 대기 중
        Map.entry("ORD-20260411-012", new OrderInfo(
                "ORD-20260411-012", "필라테스 레깅스 (스몰, 네이비)", "결제완료", 67_000, "2026-04-11",
                null, null, null,
                "결제 확인 완료. 출고 준비 중 (1~2 영업일 내 발송 예정)")),

        Map.entry("ORD-20260308-013", new OrderInfo(
                "ORD-20260308-013", "아로마 디퓨저 세트", "배송완료", 54_000, "2026-03-08",
                "로젠택배", "445566778899", "2026-03-11",
                "2026-03-11 10:30 배송 완료")),

        Map.entry("ORD-20260222-014", new OrderInfo(
                "ORD-20260222-014", "접이식 요가 블록 (2개 세트)", "배송완료", 18_000, "2026-02-22",
                "우체국택배", "556677889900", "2026-02-24",
                "2026-02-24 배송 완료")),

        // 배송 중
        Map.entry("ORD-20260409-015", new OrderInfo(
                "ORD-20260409-015", "노트북 파우치 15인치 (브라운)", "배송중", 42_000, "2026-04-09",
                "CJ대한통운", "667788990011", "2026-04-12",
                "2026-04-11 발송. 도착 예정일 내 배송 정상 진행 중")),

        Map.entry("ORD-20260315-016", new OrderInfo(
                "ORD-20260315-016", "무선 충전 패드 (고속)", "배송완료", 38_000, "2026-03-15",
                "한진택배", "778899001122", "2026-03-17",
                "2026-03-17 배송 완료")),

        // 반품 완료
        Map.entry("ORD-20260228-017", new OrderInfo(
                "ORD-20260228-017", "미니 가습기 (화이트)", "반품완료", 25_000, "2026-02-28",
                "CJ대한통운", "889900112233", "2026-03-02",
                "제품 불량으로 반품 접수 후 처리 완료. 환불 완료"))
    );

    private static final String[] FALLBACK_STATUSES  = {"배송중", "배송완료", "결제완료", "배송준비중", "취소처리중"};
    private static final String[] FALLBACK_PRODUCTS  = {"생활용품 세트", "의류 상품", "전자기기 액세서리", "식품류", "뷰티 제품"};
    private static final String[] FALLBACK_COURIERS  = {"CJ대한통운", "한진택배", "로젠택배", "우체국택배"};

    public Optional<OrderInfo> findById(String orderId) {
        return Optional.ofNullable(ORDERS.get(orderId));
    }

    /** 알려진 주문 ID를 텍스트로 포맷 */
    public String formatText(OrderInfo o) {
        StringBuilder sb = new StringBuilder();
        sb.append("주문번호: ").append(o.orderId()).append("\n");
        sb.append("상품명: ").append(o.productName()).append("\n");
        sb.append("상태: ").append(o.status()).append("\n");
        sb.append("결제금액: ").append(String.format("%,d", o.amount())).append("원\n");
        sb.append("주문일: ").append(o.orderedAt()).append("\n");
        if (o.courier() != null)           sb.append("배송사: ").append(o.courier()).append("\n");
        if (o.trackingNumber() != null)    sb.append("운송장번호: ").append(o.trackingNumber()).append("\n");
        if (o.estimatedDelivery() != null) sb.append("도착예정: ").append(o.estimatedDelivery()).append("\n");
        if (o.note() != null)              sb.append("비고: ").append(o.note()).append("\n");
        return sb.toString();
    }

    /** 알 수 없는 주문 ID에 대한 결정적(deterministic) 폴백 텍스트 */
    public String fallbackText(String orderId) {
        int hash = Math.abs(orderId.hashCode());
        return """
                주문번호: %s
                상품명: %s
                상태: %s
                결제금액: %,d원
                주문일: 2026-04-%02d
                배송사: %s
                """.formatted(
                orderId,
                FALLBACK_PRODUCTS[(hash / 7) % FALLBACK_PRODUCTS.length],
                FALLBACK_STATUSES[hash % FALLBACK_STATUSES.length],
                15_000 + (hash % 185_000),
                1 + (hash % 28),
                FALLBACK_COURIERS[(hash / 13) % FALLBACK_COURIERS.length]
        );
    }
}
