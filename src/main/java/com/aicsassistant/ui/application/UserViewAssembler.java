package com.aicsassistant.ui.application;

import com.aicsassistant.order.application.OrderService;
import com.aicsassistant.ui.viewmodel.UserView;
import com.aicsassistant.user.DummyUserStore;
import com.aicsassistant.user.DummyUserStore.DummyUser;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 유저 포털에 넘길 사용자 + 주문 목록을 조립한다. 주문 날짜는 조회 시점에 계산되어 항상 신선하다.
 */
@Service
@RequiredArgsConstructor
public class UserViewAssembler {

    private final OrderService orderService;

    public List<UserView> getAll() {
        return DummyUserStore.getAll().stream().map(this::toView).toList();
    }

    public Optional<UserView> find(String userId) {
        return DummyUserStore.find(userId).map(this::toView);
    }

    private UserView toView(DummyUser user) {
        return UserView.of(user, orderService.getOrdersByCustomer(user.id()));
    }
}
