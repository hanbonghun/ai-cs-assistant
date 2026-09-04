package com.aicsassistant.order;

import com.aicsassistant.user.DummyUserStore;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/**
 * 데모용 인메모리 주문 데이터 저장소.
 *
 * <p>실제 서비스에서는 주문 도메인 서비스 API 호출 또는 주문 도메인에서 동기화된
 * 로컬 read-model 테이블 조회로 대체합니다.
 *
 * <p><b>날짜는 조회 시점에 계산한다.</b> 주문 ID(ORD-YYYYMMDD-NNN)는 opaque 식별자로
 * 고정하되, 표시되는 날짜는 템플릿에 담긴 "오늘로부터 며칠" 오프셋을 {@code LocalDate.now()}
 * 에 적용해 만든다. 이전에는 클래스 로딩 시점에 한 번 계산해 상수로 굳혔는데, 컨테이너가
 * 오래 떠 있으면 날짜가 그대로 늙었다. 스케줄러로 갱신하는 방법도 있으나 그러면 아래
 * {@code STATUS_OVERRIDES}(승인된 환불 등 실제 변경분)를 함께 날리게 되므로 택하지 않았다.
 *
 * <p>정적 정의({@code TEMPLATES})와 런타임 변경분({@code STATUS_OVERRIDES})을 분리해 두면
 * 날짜는 항상 신선하면서 승인 이력은 보존된다.
 */
@Repository
public class InMemoryOrderRepository {

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
    ) {}

    /**
     * 주문 하나의 정적 정의. 날짜를 값이 아니라 오늘 기준 오프셋으로 갖는다.
     *
     * @param orderedDaysAgo         주문일 (오늘로부터 며칠 전)
     * @param deliveryOffsetDays     도착예정일 (음수=과거, 양수=미래, null=해당 없음)
     * @param note                   오늘 날짜를 받아 비고를 만든다. 날짜가 없는 비고는 인자를 무시한다
     */
    private record OrderTemplate(
            String productName,
            String status,
            int amount,
            int orderedDaysAgo,
            String courier,
            String trackingNumber,
            Integer deliveryOffsetDays,
            Function<LocalDate, String> note
    ) {}

    /** 데모 데이터가 KST 기준이므로 프롬프트에 넣는 "오늘"도 같은 기준이어야 한다. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static String fmt(LocalDate d) {
        return d.format(FMT);
    }

    private static OrderTemplate t(String productName, String status, int amount, int orderedDaysAgo,
                                   String courier, String trackingNumber, Integer deliveryOffsetDays,
                                   Function<LocalDate, String> note) {
        return new OrderTemplate(productName, status, amount, orderedDaysAgo,
                courier, trackingNumber, deliveryOffsetDays, note);
    }

    /** 날짜가 들어가지 않는 고정 비고. */
    private static Function<LocalDate, String> note(String text) {
        return d -> text;
    }

    private static final Map<String, OrderTemplate> TEMPLATES = Map.ofEntries(

        // 배송 지연 — 취소 요청 시나리오 (도착 예정일이 어제였는데 아직 안 옴)
        Map.entry("ORD-20260410-001", t("무선 블루투스 이어폰 AX-300", "배송중", 89_000, 5,
                "CJ대한통운", "375234567890", -1,
                note("인천 물류센터 경유 중. 예상 도착일보다 1일 지연"))),

        // 배송 완료 후 반품 요청 시나리오 (반품 가능 기간 내)
        Map.entry("ORD-20260405-002", t("코튼 후드티 (네이비, XL)", "배송완료", 45_000, 7,
                "한진택배", "123456789012", -4,
                d -> "%s 14:32 배송 완료. 반품 가능 기간: ~%s".formatted(fmt(d.minusDays(4)), fmt(d.plusDays(3))))),

        // 결제 직후 취소 가능 시나리오
        Map.entry("ORD-20260412-003", t("유기농 그래놀라 500g × 3개", "결제완료", 38_700, 1,
                null, null, null,
                note("아직 출고 전. 즉시 취소 가능"))),

        // 취소 처리 중 — 환불 대기 시나리오
        Map.entry("ORD-20260401-004", t("스탠드 조명 LD-500", "취소처리중", 128_000, 20,
                null, null, null,
                d -> "고객 요청으로 %s 취소 접수. 환불 처리 중 (영업일 2~3일 소요)".formatted(fmt(d.minusDays(18))))),

        // 교환 진행 중 시나리오
        Map.entry("ORD-20260330-005", t("러닝화 TR-200 (270mm)", "교환진행중", 112_000, 50,
                "로젠택배", "654321098765", 2,
                note("사이즈 교환 요청 접수. 수거 완료 후 재발송 예정"))),

        // 부분 환불 완료 시나리오
        Map.entry("ORD-20260320-006", t("주방용품 세트 (4종) 외 2건", "부분환불완료", 215_000, 60,
                "CJ대한통운", "987001234567", -55,
                note("총 3건 중 1건(냄비 뚜껑 파손) 부분 환불 32,000원 완료. 나머지 정상 배송"))),

        // 중복 결제 의심 시나리오
        Map.entry("ORD-20260411-007", t("보습 크림 세트", "결제완료", 56_000, 2,
                null, null, null,
                note("동일 상품 동일 시각 2건 결제 감지. 이상거래 검토 중"))),

        // 오배송 시나리오
        Map.entry("ORD-20260407-008", t("요가 매트 6mm (퍼플)", "배송완료", 35_000, 14,
                "한진택배", "112233445566", -12,
                d -> "%s 배송 완료. 고객 수령 상품이 주문 상품과 다를 수 있음 (오배송 신고 접수됨)"
                        .formatted(fmt(d.minusDays(12))))),

        // 정상 배송 완료 (단순 조회)
        Map.entry("ORD-20260325-009", t("애플 워치 밴드 (44mm, 블랙)", "배송완료", 29_000, 55,
                "CJ대한통운", "223344556677", -53,
                d -> "%s 11:20 배송 완료".formatted(fmt(d.minusDays(53))))),

        Map.entry("ORD-20260301-010", t("기계식 키보드 TK-65", "배송완료", 145_000, 80,
                "한진택배", "334455667788", -77,
                d -> "%s 14:05 배송 완료".formatted(fmt(d.minusDays(77))))),

        // 고객 취소 완료
        Map.entry("ORD-20260218-011", t("텀블러 500ml (매트 그레이)", "취소완료", 32_000, 90,
                null, null, null,
                d -> "%s 고객 요청으로 즉시 취소. 환불 완료 (카드 취소 2~3 영업일 소요)"
                        .formatted(fmt(d.minusDays(90))))),

        // 출고 대기 중
        Map.entry("ORD-20260411-012", t("필라테스 레깅스 (스몰, 네이비)", "결제완료", 67_000, 2,
                null, null, null,
                note("결제 확인 완료. 출고 준비 중 (1~2 영업일 내 발송 예정)"))),

        Map.entry("ORD-20260308-013", t("아로마 디퓨저 세트", "배송완료", 54_000, 72,
                "로젠택배", "445566778899", -69,
                d -> "%s 10:30 배송 완료".formatted(fmt(d.minusDays(69))))),

        Map.entry("ORD-20260222-014", t("접이식 요가 블록 (2개 세트)", "배송완료", 18_000, 85,
                "우체국택배", "556677889900", -83,
                d -> "%s 배송 완료".formatted(fmt(d.minusDays(83))))),

        // 배송 중 — 정상 진행
        Map.entry("ORD-20260409-015", t("노트북 파우치 15인치 (브라운)", "배송중", 42_000, 6,
                "CJ대한통운", "667788990011", 1,
                d -> "%s 발송. 도착 예정일 내 배송 정상 진행 중".formatted(fmt(d.minusDays(4))))),

        Map.entry("ORD-20260315-016", t("무선 충전 패드 (고속)", "배송완료", 38_000, 65,
                "한진택배", "778899001122", -63,
                d -> "%s 배송 완료".formatted(fmt(d.minusDays(63))))),

        // 반품 완료
        Map.entry("ORD-20260228-017", t("미니 가습기 (화이트)", "반품완료", 25_000, 78,
                "CJ대한통운", "889900112233", -76,
                note("제품 불량으로 반품 접수 후 처리 완료. 환불 완료")))
    );

    /**
     * 런타임에 바뀐 주문 상태(환불 승인 등). 정적 정의를 덮어쓴다.
     *
     * <p>ponytail: static mutable — 데모 전체가 공유하고 재시작하면 초기화된다.
     * 실제 서비스에서는 주문 도메인 API 호출로 대체된다.
     */
    private static final Map<String, String> STATUS_OVERRIDES = new ConcurrentHashMap<>();

    /** 주문 소유자 — 데모 사용자 데이터에서 파생하므로 두 곳에 중복 정의되지 않는다. */
    private static final Map<String, String> OWNER_BY_ORDER = DummyUserStore.getAll().stream()
            .flatMap(u -> u.orderIds().stream().map(id -> Map.entry(id, u.id())))
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
        return Optional.ofNullable(TEMPLATES.get(orderId)).map(tpl -> assemble(orderId, tpl));
    }

    /** 한 고객의 주문 목록 — 최근 주문이 먼저. 유저 포털 화면이 쓴다. */
    public List<OrderInfo> findAllByCustomer(String customerIdentifier) {
        return TEMPLATES.entrySet().stream()
                .filter(e -> Objects.equals(OWNER_BY_ORDER.get(e.getKey()), customerIdentifier))
                .sorted(Comparator.comparingInt(e -> e.getValue().orderedDaysAgo()))
                .map(e -> assemble(e.getKey(), e.getValue()))
                .toList();
    }

    /** 템플릿 + 오늘 날짜 + 런타임 상태를 합쳐 조회 결과를 만든다. */
    private static OrderInfo assemble(String orderId, OrderTemplate tpl) {
        LocalDate today = LocalDate.now(KST);
        return new OrderInfo(
                orderId,
                tpl.productName(),
                STATUS_OVERRIDES.getOrDefault(orderId, tpl.status()),
                tpl.amount(),
                fmt(today.minusDays(tpl.orderedDaysAgo())),
                tpl.courier(),
                tpl.trackingNumber(),
                tpl.deliveryOffsetDays() == null ? null : fmt(today.plusDays(tpl.deliveryOffsetDays())),
                tpl.note().apply(today));
    }

    /** 환불 승인 실행 — 주문 상태만 바꾼다. 금액 이력은 남기지 않는다(mock 한계). */
    public void markRefunded(String orderId) {
        if (TEMPLATES.containsKey(orderId)) {
            STATUS_OVERRIDES.put(orderId, "환불완료");
        }
    }

    /** ponytail: static mutable 상태를 테스트 간 격리하기 위한 복구 훅. 운영 코드에서 호출하지 않는다. */
    public void resetForTest() {
        STATUS_OVERRIDES.clear();
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
}
