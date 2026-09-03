package com.aicsassistant.staging.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.aicsassistant.order.InMemoryOrderRepository.OrderInfo;
import com.aicsassistant.staging.domain.ChangeType;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.dto.StagedChangeDecisionRequest;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 동시 승인 방지 가드가 실제로 {@code ALREADY_DECIDED} 로 변환되는지 검증한다.
 * {@code @Version}(같은 행의 동시 결정)과 {@code uq_staged_change_order_approved}
 * (같은 주문의 서로 다른 PENDING 행이 동시에 승인되는 경우) 두 경합을 모두 다룬다.
 *
 * <p>두 트랜잭션이 동시에 경합하는 진짜 상황은 스레드 없이는 결정적으로 재현할 수 없다.
 * 대신 {@code stagedChangeRepository.saveAndFlush(...)} 가 해당 예외를 던지는 상황을
 * 직접 흉내낸다 — 이 테스트의 대상은 서비스가 그 예외를 실제로 캐치해 재해석하는지이지,
 * JPA 의 버전 충돌 감지나 DB 유니크 인덱스 자체(그건 엔티티 매핑·스키마의 몫이고 통합
 * 테스트가 부팅 시점에 이미 검증한다)가 아니다.
 */
@ExtendWith(MockitoExtension.class)
class StagedChangeApprovalServiceOptimisticLockTest {

    private static final Long INQUIRY_ID = 1L;
    private static final Long CHANGE_ID = 100L;
    private static final String ORDER_ID = "ORD-20260405-002";
    private static final String CUSTOMER_ID = "cust-001";

    @Mock StagedChangeRepository stagedChangeRepository;
    @Mock InquiryRepository inquiryRepository;
    @Mock InquiryMessageRepository messageRepository;
    @Mock InMemoryOrderRepository orderRepository;

    StagedChangeApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new StagedChangeApprovalService(
                stagedChangeRepository, inquiryRepository, messageRepository, orderRepository);
    }

    private StagedChange pendingChange() {
        return StagedChange.propose(INQUIRY_ID, ChangeType.REFUND, ORDER_ID, 45_000, "사유", "정책");
    }

    @Test
    void approveTranslatesOptimisticLockFailureIntoAlreadyDecided() {
        StagedChange change = pendingChange();
        when(stagedChangeRepository.findById(CHANGE_ID)).thenReturn(Optional.of(change));
        when(inquiryRepository.findById(INQUIRY_ID))
                .thenReturn(Optional.of(Inquiry.create(CUSTOMER_ID, "문의", "환불 요청", null, null, ORDER_ID)));
        when(orderRepository.findById(ORDER_ID, CUSTOMER_ID)).thenReturn(Optional.of(
                new OrderInfo(ORDER_ID, "상품", "배송완료", 45_000, "2026-01-01", null, null, null, null)));
        when(stagedChangeRepository.saveAndFlush(change))
                .thenThrow(new ObjectOptimisticLockingFailureException(StagedChange.class, CHANGE_ID));

        assertThatThrownBy(() -> approvalService.approve(INQUIRY_ID, CHANGE_ID,
                new StagedChangeDecisionRequest("counselor-demo", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ALREADY_DECIDED");

        // flush 를 markRefunded·알림 저장보다 앞에 뒀으므로, 패자는 둘 다 건드리지 않고 끝나야 한다
        verify(orderRepository, never()).markRefunded(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void approveTranslatesUniqueIndexViolationIntoAlreadyDecided() {
        StagedChange change = pendingChange();
        when(stagedChangeRepository.findById(CHANGE_ID)).thenReturn(Optional.of(change));
        when(inquiryRepository.findById(INQUIRY_ID))
                .thenReturn(Optional.of(Inquiry.create(CUSTOMER_ID, "문의", "환불 요청", null, null, ORDER_ID)));
        when(orderRepository.findById(ORDER_ID, CUSTOMER_ID)).thenReturn(Optional.of(
                new OrderInfo(ORDER_ID, "상품", "배송완료", 45_000, "2026-01-01", null, null, null, null)));
        when(stagedChangeRepository.saveAndFlush(change))
                .thenThrow(new DataIntegrityViolationException("uq_staged_change_order_approved"));

        assertThatThrownBy(() -> approvalService.approve(INQUIRY_ID, CHANGE_ID,
                new StagedChangeDecisionRequest("counselor-demo", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ALREADY_DECIDED");

        // flush 를 markRefunded·알림 저장보다 앞에 뒀으므로, 패자는 둘 다 건드리지 않고 끝나야 한다
        verify(orderRepository, never()).markRefunded(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void rejectTranslatesOptimisticLockFailureIntoAlreadyDecided() {
        StagedChange change = pendingChange();
        when(stagedChangeRepository.findById(CHANGE_ID)).thenReturn(Optional.of(change));
        when(stagedChangeRepository.saveAndFlush(change))
                .thenThrow(new ObjectOptimisticLockingFailureException(StagedChange.class, CHANGE_ID));

        assertThatThrownBy(() -> approvalService.reject(INQUIRY_ID, CHANGE_ID,
                new StagedChangeDecisionRequest("counselor-demo", "사유", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ALREADY_DECIDED");
    }
}
