package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.domain.AnalysisStatus;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 실패했거나 유실된 분석을 주워 다시 돌린다.
 *
 * <p>{@code @Async} 이벤트로만 분석을 걸던 구조에는 실패를 다시 집어 올릴 훅이 없었다.
 * 상태를 DB(분석 로그)에 두므로 배포와 재시작을 넘어 살아남고, 프로세스가 죽어 RUNNING 이
 * 남은 경우도 같은 쿼리로 주워진다. 근거는 ADR 0009.
 *
 * <p>{@code @Scheduled} 빈은 스케줄링 후처리기가 메서드를 찾아야 하므로
 * {@code spring.main.lazy-initialization} 을 우회해 즉시 생성된다. DB 없이 컨텍스트 로딩만
 * 확인하는 테스트({@code AiCsAssistantApplicationTests})가 DataSource 오토컨피그를 빼고 도는데,
 * 그 컨텍스트에서는 이 빈이 레포지터리를 끌어와 기동을 깨뜨린다. DB 가 없으면 스위퍼도 없는 게
 * 맞으므로 프로퍼티로 끌 수 있게 한다 — 운영에서 잠시 내리는 스위치로도 쓴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.analysis.retry.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class AnalysisRetryScheduler {

    /** 재시도 상한. PromptFactory 의 3회 재질문 상한과 같은 수를 쓴다. */
    static final int MAX_RETRY_ATTEMPTS = 3;

    private static final List<InquiryStatus> RETRYABLE_INQUIRY_STATUSES =
            List.of(InquiryStatus.NEW, InquiryStatus.PENDING_CUSTOMER);

    private final InquiryAnalysisLogRepository logRepository;
    private final InquiryAnalysisService analysisService;

    @Scheduled(fixedDelay = 2, timeUnit = TimeUnit.MINUTES)
    public void retryStalledAnalyses() {
        List<Long> inquiryIds = logRepository.findInquiryIdsNeedingRetry(
                RETRYABLE_INQUIRY_STATUSES,
                AnalysisStatus.FAILURE,
                AnalysisStatus.RUNNING,
                LocalDateTime.now().minus(AnalysisLogService.RUNNING_STALE_AFTER)
        );
        if (inquiryIds.isEmpty()) {
            return;
        }
        log.info("[분석 재시도] 대상 {}건", inquiryIds.size());

        for (Long inquiryId : inquiryIds) {
            // ponytail: 문의 생애 전체의 FAILURE 를 센다. 고객 답변 후 재분석에 새 예산을 주려면
            // 시도 세대를 추적해야 하는데, 데모 트래픽에서는 과하다.
            long failures = logRepository.countByInquiryIdAndAnalysisStatus(
                    inquiryId, AnalysisStatus.FAILURE);
            if (failures >= MAX_RETRY_ATTEMPTS) {
                // Task 5 에서 에스컬레이션으로 채운다
                continue;
            }
            try {
                log.info("[분석 재시도] inquiryId={} 이전 실패={}건", inquiryId, failures);
                analysisService.analyze(inquiryId);
            } catch (Exception e) {
                log.warn("[분석 재시도] 실패 inquiryId={}", inquiryId, e);
            }
        }
    }
}
