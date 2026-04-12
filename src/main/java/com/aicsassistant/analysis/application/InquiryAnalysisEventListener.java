package com.aicsassistant.analysis.application;

import com.aicsassistant.inquiry.application.InquiryCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class InquiryAnalysisEventListener {

    private final InquiryAnalysisService inquiryAnalysisService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInquiryCreated(InquiryCreatedEvent event) {
        log.info("[자동 분석] 문의 등록 감지 inquiryId={}", event.inquiryId());
        try {
            inquiryAnalysisService.analyze(event.inquiryId());
        } catch (Exception e) {
            log.error("[자동 분석] 실패 inquiryId={}", event.inquiryId(), e);
        }
    }
}
