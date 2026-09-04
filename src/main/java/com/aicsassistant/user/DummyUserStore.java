package com.aicsassistant.user;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 데모용 더미 사용자 데이터.
 *
 * <p>여기는 <b>누가 어떤 주문의 주인인지</b>만 갖는다. 상품명·상태·금액·주문일 같은 주문 상세는
 * {@code InMemoryOrderRepository} 가 단독으로 소유한다. 예전에는 양쪽이 같은 정보를 각자 들고
 * 있었고 이쪽 날짜만 하드코딩이라, 유저 포털은 "2026-04-10" 을 보여주는데 에이전트가 조회한
 * 주문 정보는 다른 날짜를 말하는 어긋남이 있었다. 한쪽만 진실을 갖게 해 그 어긋남을 없앤다.
 */
public class DummyUserStore {

    public record DummyUser(
            String id,
            String name,
            String email,
            String phone,
            List<String> orderIds
    ) {}

    private static final List<DummyUser> USERS = List.of(
            new DummyUser("cust-001", "김민준", "minjun.kim@example.com", "010-1234-5678", List.of(
                    "ORD-20260410-001",
                    "ORD-20260405-002",
                    "ORD-20260412-003",
                    "ORD-20260325-009",
                    "ORD-20260301-010",
                    "ORD-20260218-011"
            )),
            new DummyUser("cust-002", "이서연", "seoyeon.lee@example.com", "010-2345-6789", List.of(
                    "ORD-20260401-004",
                    "ORD-20260330-005",
                    "ORD-20260411-012",
                    "ORD-20260308-013",
                    "ORD-20260222-014"
            )),
            new DummyUser("cust-003", "박지호", "jiho.park@example.com", "010-3456-7890", List.of(
                    "ORD-20260320-006",
                    "ORD-20260411-007",
                    "ORD-20260407-008",
                    "ORD-20260409-015",
                    "ORD-20260315-016",
                    "ORD-20260228-017"
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
