package com.aicsassistant.order.application;

import com.aicsassistant.order.dto.OrderInfo;
import com.aicsassistant.order.infra.InMemoryOrderRepository;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 다른 도메인이 주문을 읽고 바꾸는 입구. 저장소({@link InMemoryOrderRepository})를 직접 잡지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    /** 데모 주문 데이터의 기준 시간대. 프롬프트에 넣는 "오늘" 도 같은 기준이어야 한다. */
    public static final ZoneId ZONE = InMemoryOrderRepository.KST;

    private final InMemoryOrderRepository orderRepository;

    /** 본인 주문만 돌려준다 — 남의 주문이면 존재 여부도 드러내지 않고 비어 있다. */
    public Optional<OrderInfo> findOrder(String orderId, String customerIdentifier) {
        return orderRepository.findById(orderId, customerIdentifier);
    }

    public List<OrderInfo> getOrdersByCustomer(String customerIdentifier) {
        return orderRepository.findAllByCustomer(customerIdentifier);
    }

    public void markRefunded(String orderId) {
        orderRepository.markRefunded(orderId);
    }
}
