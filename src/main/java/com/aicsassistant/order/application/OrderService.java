package com.aicsassistant.order.application;

import com.aicsassistant.order.infra.InMemoryOrderRepository;
import com.aicsassistant.order.infra.InMemoryOrderRepository.OrderInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 다른 도메인이 주문을 읽는 입구. 저장소({@link InMemoryOrderRepository})를 직접 잡지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final InMemoryOrderRepository orderRepository;

    public List<OrderInfo> getOrdersByCustomer(String customerIdentifier) {
        return orderRepository.findAllByCustomer(customerIdentifier);
    }
}
