package com.aicsassistant.inquiry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 비동기 reply 흐름에서 가장 중요한 계약 — {@link InquiryService#replyAsCustomer}가
 * CustomerReplyEvent를 publish하는지 — 를 잠근다.
 */
@ExtendWith(MockitoExtension.class)
class InquiryServiceReplyTest {

    @Mock InquiryRepository inquiryRepository;
    @Mock InquiryAnalysisLogRepository inquiryAnalysisLogRepository;
    @Mock InquiryMessageRepository inquiryMessageRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks InquiryService service;

    @Test
    void publishesCustomerReplyEvent_whenInquiryInPendingCustomer() {
        Inquiry inquiry = Inquiry.create("cust-1", "title", "content");
        inquiry.askFollowUp();  // → PENDING_CUSTOMER
        when(inquiryRepository.findById(1L)).thenReturn(Optional.of(inquiry));

        service.replyAsCustomer(1L, "ORD-20260101-001 입니다");

        // 메시지가 저장된다
        verify(inquiryMessageRepository, times(1)).save(any());
        // 그리고 CustomerReplyEvent가 발행된다 (해당 inquiryId로)
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue())
                .isInstanceOfSatisfying(CustomerReplyEvent.class,
                        e -> assertThat(e.inquiryId()).isEqualTo(1L));
    }

    @Test
    void rejects_whenInquiryNotInPendingCustomer() {
        Inquiry inquiry = Inquiry.create("cust-1", "title", "content");  // 기본 NEW
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.NEW);
        when(inquiryRepository.findById(1L)).thenReturn(Optional.of(inquiry));

        assertThatThrownBy(() -> service.replyAsCustomer(1L, "응답"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PENDING_CUSTOMER");

        verify(inquiryMessageRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejects_whenContentBlank() {
        Inquiry inquiry = Inquiry.create("cust-1", "title", "content");
        inquiry.askFollowUp();
        when(inquiryRepository.findById(1L)).thenReturn(Optional.of(inquiry));

        assertThatThrownBy(() -> service.replyAsCustomer(1L, "   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("답변 내용");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejects_whenInquiryNotFound() {
        when(inquiryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replyAsCustomer(99L, "응답"))
                .isInstanceOf(ApiException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
