package com.aicsassistant.order;

import com.aicsassistant.user.DummyUserStore;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/**
 * 데모용 인메모리 주문 데이터 저장소.
 *
 * <p>실제 서비스에서는 주문 도메인 서비스 API 호출 또는
 * 주문 도메인에서 동기화된 로컬 read-model 테이블 조회로 대체합니다.
 *
 * <p>주문 ID(ORD-YYYYMMDD-NNN)는 opaque 식별자로 고정하지만, 화면에 표시되는
 * 날짜(orderedAt / estimatedDelivery / note 안의 날짜)는 부팅 시점 기준 상대값으로
 * 계산하여 데모가 시간이 흘러도 fresh하게 보이도록 한다.
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

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final LocalDate TODAY = LocalDate.now(KST);

    private static String daysAgo(int days) {
        return TODAY.minusDays(days).format(FMT);
    }

    private static String daysAhead(int days) {
        return TODAY.plusDays(days).format(FMT);
    }

    private static final Map<String, OrderInfo> INITIAL_ORDERS = Map.ofEntries(

        // 배송 지연 — 취소 요청 시나리오 (도착 예정일이 어제였는데 아직 안 옴)
        Map.entry("ORD-20260410-001", new OrderInfo(
                "ORD-20260410-001", "무선 블루투스 이어폰 AX-300", "배송중", 89_000, daysAgo(5),
                "CJ대한통운", "375234567890", daysAgo(1),
                "인천 물류센터 경유 중. 예상 도착일보다 1일 지연")),

        // 배송 완료 후 반품 요청 시나리오 (반품 가능 기간 내)
        Map.entry("ORD-20260405-002", new OrderInfo(
                "ORD-20260405-002", "코튼 후드티 (네이비, XL)", "배송완료", 45_000, daysAgo(7),
                "한진택배", "123456789012", daysAgo(4),
                "%s 14:32 배송 완료. 반품 가능 기간: ~%s".formatted(daysAgo(4), daysAhead(3)))),

        // 결제 직후 취소 가능 시나리오
        Map.entry("ORD-20260412-003", new OrderInfo(
                "ORD-20260412-003", "유기농 그래놀라 500g × 3개", "결제완료", 38_700, daysAgo(1),
                null, null, null,
                "아직 출고 전. 즉시 취소 가능")),

        // 취소 처리 중 — 환불 대기 시나리오
        Map.entry("ORD-20260401-004", new OrderInfo(
                "ORD-20260401-004", "스탠드 조명 LD-500", "취소처리중", 128_000, daysAgo(20),
                null, null, null,
                "고객 요청으로 %s 취소 접수. 환불 처리 중 (영업일 2~3일 소요)".formatted(daysAgo(18)))),

        // 교환 진행 중 시나리오
        Map.entry("ORD-20260330-005", new OrderInfo(
                "ORD-20260330-005", "러닝화 TR-200 (270mm)", "교환진행중", 112_000, daysAgo(50),
                "로젠택배", "654321098765", daysAhead(2),
                "사이즈 교환 요청 접수. 수거 완료 후 재발송 예정")),

        // 부분 환불 완료 시나리오
        Map.entry("ORD-20260320-006", new OrderInfo(
                "ORD-20260320-006", "주방용품 세트 (4종) 외 2건", "부분환불완료", 215_000, daysAgo(60),
                "CJ대한통운", "987001234567", daysAgo(55),
                "총 3건 중 1건(냄비 뚜껑 파손) 부분 환불 32,000원 완료. 나머지 정상 배송")),

        // 중복 결제 의심 시나리오
        Map.entry("ORD-20260411-007", new OrderInfo(
                "ORD-20260411-007", "보습 크림 세트", "결제완료", 56_000, daysAgo(2),
                null, null, null,
                "동일 상품 동일 시각 2건 결제 감지. 이상거래 검토 중")),

        // 오배송 시나리오
        Map.entry("ORD-20260407-008", new OrderInfo(
                "ORD-20260407-008", "요가 매트 6mm (퍼플)", "배송완료", 35_000, daysAgo(14),
                "한진택배", "112233445566", daysAgo(12),
                "%s 배송 완료. 고객 수령 상품이 주문 상품과 다를 수 있음 (오배송 신고 접수됨)".formatted(daysAgo(12)))),

        // 정상 배송 완료 (단순 조회)
        Map.entry("ORD-20260325-009", new OrderInfo(
                "ORD-20260325-009", "애플 워치 밴드 (44mm, 블랙)", "배송완료", 29_000, daysAgo(55),
                "CJ대한통운", "223344556677", daysAgo(53),
                "%s 11:20 배송 완료".formatted(daysAgo(53)))),

        Map.entry("ORD-20260301-010", new OrderInfo(
                "ORD-20260301-010", "기계식 키보드 TK-65", "배송완료", 145_000, daysAgo(80),
                "한진택배", "334455667788", daysAgo(77),
                "%s 14:05 배송 완료".formatted(daysAgo(77)))),

        // 고객 취소 완료
        Map.entry("ORD-20260218-011", new OrderInfo(
                "ORD-20260218-011", "텀블러 500ml (매트 그레이)", "취소완료", 32_000, daysAgo(90),
                null, null, null,
                "%s 고객 요청으로 즉시 취소. 환불 완료 (카드 취소 2~3 영업일 소요)".formatted(daysAgo(90)))),

        // 출고 대기 중
        Map.entry("ORD-20260411-012", new OrderInfo(
                "ORD-20260411-012", "필라테스 레깅스 (스몰, 네이비)", "결제완료", 67_000, daysAgo(2),
                null, null, null,
                "결제 확인 완료. 출고 준비 중 (1~2 영업일 내 발송 예정)")),

        Map.entry("ORD-20260308-013", new OrderInfo(
                "ORD-20260308-013", "아로마 디퓨저 세트", "배송완료", 54_000, daysAgo(72),
                "로젠택배", "445566778899", daysAgo(69),
                "%s 10:30 배송 완료".formatted(daysAgo(69)))),

        Map.entry("ORD-20260222-014", new OrderInfo(
                "ORD-20260222-014", "접이식 요가 블록 (2개 세트)", "배송완료", 18_000, daysAgo(85),
                "우체국택배", "556677889900", daysAgo(83),
                "%s 배송 완료".formatted(daysAgo(83)))),

        // 배송 중 — 정상 진행
        Map.entry("ORD-20260409-015", new OrderInfo(
                "ORD-20260409-015", "노트북 파우치 15인치 (브라운)", "배송중", 42_000, daysAgo(6),
                "CJ대한통운", "667788990011", daysAhead(1),
                "%s 발송. 도착 예정일 내 배송 정상 진행 중".formatted(daysAgo(4)))),

        Map.entry("ORD-20260315-016", new OrderInfo(
                "ORD-20260315-016", "무선 충전 패드 (고속)", "배송완료", 38_000, daysAgo(65),
                "한진택배", "778899001122", daysAgo(63),
                "%s 배송 완료".formatted(daysAgo(63)))),

        // 반품 완료
        Map.entry("ORD-20260228-017", new OrderInfo(
                "ORD-20260228-017", "미니 가습기 (화이트)", "반품완료", 25_000, daysAgo(78),
                "CJ대한통운", "889900112233", daysAgo(76),
                "제품 불량으로 반품 접수 후 처리 완료. 환불 완료"))
    );

    // ponytail: static mutable — 데모 전체가 공유하고 재시작하면 초기화된다.
    // 실제 서비스에서는 주문 도메인 API 호출로 대체된다.
    private static final Map<String, OrderInfo> ORDERS = new ConcurrentHashMap<>(INITIAL_ORDERS);

    /** 주문 소유자 — 데모 사용자 데이터에서 파생하므로 두 곳에 중복 정의되지 않는다. */
    private static final Map<String, String> OWNER_BY_ORDER = DummyUserStore.getAll().stream()
            .flatMap(u -> u.orders().stream().map(o -> Map.entry(o.orderId(), u.id())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    /**
     * 주문을 조회한다. 요청자 소유가 아닌 주문은 존재하지 않는 것으로 취급한다.
     *
     * <p>소유자가 아닐 때 PERMISSION이 아니라 빈 결과(→ NOT_FOUND)를 주는 이유: 둘을 구분해주면
     * 주문번호를 하나씩 넣어보며 타인의 주문 존재 여부를 알아낼 수 있다.
     *
     * <p>ponytail: 이 검증은 customerIdentifier 만큼만 강하다. 데모는 이 값을 클라이언트에서
     * 그대로 받으므로(inquiry-new.html), 실제 서비스에서는 인증된 세션에서 해결해야 한다.
     */
    public Optional<OrderInfo> findById(String orderId, String customerIdentifier) {
        if (!Objects.equals(OWNER_BY_ORDER.get(orderId), customerIdentifier)) {
            return Optional.empty();
        }
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

    /** 환불 승인 실행 — 주문 상태만 바꾼다. 금액 이력은 남기지 않는다(mock 한계). */
    public void markRefunded(String orderId) {
        ORDERS.computeIfPresent(orderId, (id, o) -> new OrderInfo(
                o.orderId(), o.productName(), "환불완료", o.amount(), o.orderedAt(),
                o.courier(), o.trackingNumber(), o.estimatedDelivery(), o.note()));
    }

    /** ponytail: static mutable 상태를 테스트 간 격리하기 위한 복구 훅. 운영 코드에서 호출하지 않는다. */
    public void resetForTest() {
        ORDERS.clear();
        ORDERS.putAll(INITIAL_ORDERS);
    }

}
