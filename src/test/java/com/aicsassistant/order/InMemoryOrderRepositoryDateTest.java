package com.aicsassistant.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 더미 주문의 날짜가 조회 시점 기준으로 계산되는지 검증한다.
 *
 * <p>예전에는 클래스 로딩 시점에 한 번 계산해 굳혔기 때문에, 컨테이너가 오래 떠 있으면
 * 데모에 늙은 날짜가 그대로 보였다. 이 테스트는 "오늘로부터 며칠" 관계가 유지되는지를 본다.
 */
class InMemoryOrderRepositoryDateTest {

    private final InMemoryOrderRepository repository = new InMemoryOrderRepository();

    @AfterEach
    void reset() {
        repository.resetForTest();
    }

    @Test
    void datesAreComputedRelativeToToday() {
        LocalDate today = LocalDate.now(InMemoryOrderRepository.KST);

        var order = repository.findById("ORD-20260410-001", "cust-001").orElseThrow();

        assertThat(order.orderedAt()).isEqualTo(today.minusDays(5).toString());
        // 이 시나리오는 "도착 예정일이 이미 지났는데 아직 안 온" 배송이다 — 어제여야 한다
        assertThat(order.estimatedDelivery()).isEqualTo(today.minusDays(1).toString());
        assertThat(LocalDate.parse(order.estimatedDelivery())).isBefore(today);
    }

    @Test
    void datesEmbeddedInNoteAreAlsoRelative() {
        LocalDate today = LocalDate.now(InMemoryOrderRepository.KST);

        var order = repository.findById("ORD-20260405-002", "cust-001").orElseThrow();

        assertThat(order.note())
                .contains(today.minusDays(4).toString())
                .contains(today.plusDays(3).toString());
    }

    @Test
    void customerOrderListIsNewestFirstAndOwnerScoped() {
        var orders = repository.findAllByCustomer("cust-001");

        assertThat(orders).hasSize(6);
        assertThat(orders.get(0).orderId()).isEqualTo("ORD-20260412-003");   // 1일 전 = 가장 최근
        assertThat(orders).extracting(InMemoryOrderRepository.OrderInfo::orderId)
                .doesNotContain("ORD-20260401-004");                          // cust-002 소유
    }

    @Test
    void refundOverrideSurvivesDateRecomputation() {
        repository.markRefunded("ORD-20260405-002");

        var order = repository.findById("ORD-20260405-002", "cust-001").orElseThrow();

        // 날짜는 매 조회마다 새로 계산되지만 승인된 상태 변경은 보존되어야 한다
        assertThat(order.status()).isEqualTo("환불완료");
        assertThat(order.orderedAt())
                .isEqualTo(LocalDate.now(InMemoryOrderRepository.KST).minusDays(7).toString());
    }
}
