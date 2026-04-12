package com.aicsassistant.user;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 데모용 더미 사용자 데이터.
 * 주문 상세 데이터는 InMemoryOrderRepository에서 관리합니다.
 */
public class DummyUserStore {

    public record UserOrder(
            String orderId,
            String productName,
            String status,
            int amount,
            String orderedAt
    ) {}

    public record DummyUser(
            String id,
            String name,
            String email,
            String phone,
            List<UserOrder> orders
    ) {}

    private static final List<DummyUser> USERS = List.of(
            new DummyUser("cust-001", "김민준", "minjun.kim@example.com", "010-1234-5678", List.of(
                    new UserOrder("ORD-20260410-001", "무선 블루투스 이어폰 AX-300",    "배송중",       89_000, "2026-04-10"),
                    new UserOrder("ORD-20260405-002", "코튼 후드티 (네이비, XL)",        "배송완료",     45_000, "2026-04-05"),
                    new UserOrder("ORD-20260412-003", "유기농 그래놀라 500g × 3개",      "결제완료",     38_700, "2026-04-12"),
                    new UserOrder("ORD-20260325-009", "애플 워치 밴드 (44mm, 블랙)",     "배송완료",     29_000, "2026-03-25"),
                    new UserOrder("ORD-20260301-010", "기계식 키보드 TK-65",             "배송완료",    145_000, "2026-03-01"),
                    new UserOrder("ORD-20260218-011", "텀블러 500ml (매트 그레이)",       "취소완료",     32_000, "2026-02-18")
            )),
            new DummyUser("cust-002", "이서연", "seoyeon.lee@example.com", "010-2345-6789", List.of(
                    new UserOrder("ORD-20260401-004", "스탠드 조명 LD-500",              "취소처리중",  128_000, "2026-04-01"),
                    new UserOrder("ORD-20260330-005", "러닝화 TR-200 (270mm)",            "교환진행중",  112_000, "2026-03-30"),
                    new UserOrder("ORD-20260411-012", "필라테스 레깅스 (스몰, 네이비)",   "결제완료",     67_000, "2026-04-11"),
                    new UserOrder("ORD-20260308-013", "아로마 디퓨저 세트",               "배송완료",     54_000, "2026-03-08"),
                    new UserOrder("ORD-20260222-014", "접이식 요가 블록 (2개 세트)",      "배송완료",     18_000, "2026-02-22")
            )),
            new DummyUser("cust-003", "박지호", "jiho.park@example.com", "010-3456-7890", List.of(
                    new UserOrder("ORD-20260320-006", "주방용품 세트 (4종) 외 2건",       "부분환불완료", 215_000, "2026-03-20"),
                    new UserOrder("ORD-20260411-007", "보습 크림 세트",                   "결제완료",     56_000, "2026-04-11"),
                    new UserOrder("ORD-20260407-008", "요가 매트 6mm (퍼플)",             "배송완료",     35_000, "2026-04-07"),
                    new UserOrder("ORD-20260409-015", "노트북 파우치 15인치 (브라운)",     "배송중",       42_000, "2026-04-09"),
                    new UserOrder("ORD-20260315-016", "무선 충전 패드 (고속)",             "배송완료",     38_000, "2026-03-15"),
                    new UserOrder("ORD-20260228-017", "미니 가습기 (화이트)",              "반품완료",     25_000, "2026-02-28")
            ))
    );

    private static final Map<String, DummyUser> BY_ID = USERS.stream()
            .collect(Collectors.toMap(DummyUser::id, Function.identity()));

    public static List<DummyUser> getAll() {
        return USERS;
    }

    public static Optional<DummyUser> find(String userId) {
        return Optional.ofNullable(BY_ID.get(userId));
    }
}
