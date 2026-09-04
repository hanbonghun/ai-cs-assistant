package com.aicsassistant.ui.viewmodel;

import com.aicsassistant.order.InMemoryOrderRepository.OrderInfo;
import com.aicsassistant.user.DummyUserStore.DummyUser;
import java.util.List;

/**
 * 유저 포털 화면에 넘기는 사용자 + 주문 목록.
 *
 * <p>{@code DummyUserStore} 는 누가 어떤 주문의 주인인지만 알고, 주문 상세는
 * {@code InMemoryOrderRepository} 가 갖는다. 그 둘을 화면 직전에 합친다 —
 * 양쪽이 같은 정보를 각자 들고 있어 날짜가 어긋나던 문제를 없애기 위한 구조다.
 *
 * <p>필드 이름은 이전에 템플릿이 쓰던 것과 같게 유지했다({@code user.orders},
 * {@code order.productName} …). 템플릿은 손대지 않는다.
 */
public record UserView(
        String id,
        String name,
        String email,
        String phone,
        List<OrderInfo> orders
) {
    public static UserView of(DummyUser user, List<OrderInfo> orders) {
        return new UserView(user.id(), user.name(), user.email(), user.phone(), orders);
    }
}
