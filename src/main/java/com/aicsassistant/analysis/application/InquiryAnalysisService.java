package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.agent.AgentResult;
import com.aicsassistant.analysis.agent.InquiryAgentService;
import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.DraftAnswerDto;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 문의 분석 유스케이스.
 *
 * <p>트랜잭션은 세 조각으로 나뉜다 — 읽기 / 에이전트 실행 / 결과 저장. 에이전트가 LLM 을
 * 십수 초 호출하는 구간에는 DB 커넥션을 잡지 않는다. 2026-09-04 에 커넥션을 14.2초 잡은 채
 * Railway Postgres 절전으로 끊겨 문의가 유실된 사고가 있었다. 근거는 ADR 0009.
 *
 * <p>경계를 {@code @Transactional} 두 개로 나누지 않은 이유: 같은 빈 안에서 호출하면 프록시를
 * 타지 않아 트랜잭션이 조용히 걸리지 않는다. {@link TransactionTemplate} 은 그렇게 실패하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryAnalysisService {

    private static final String AUTO_PROCESSOR = "ai-auto";

    private final InquiryRepository inquiryRepository;
    private final InquiryMessageRepository messageRepository;
    private final InquiryAgentService agentService;
    private final AnalysisLogService analysisLogService;
    private final CounselorNotificationService notificationService;
    private final TransactionTemplate txTemplate;

    public InquiryAnalysisResponse analyze(Long inquiryId) {
        AnalysisContext ctx = loadContext(inquiryId);
        AgentResult result = runAgent(ctx);

        if (result instanceof AgentResult.FollowUpQuestion fq) {
            return persistFollowUp(ctx, fq);
        }
        if (result instanceof AgentResult.FinalAnswer fa) {
            PersistedFinalAnswer persisted = persistFinalAnswer(ctx, fa);
            notifyCounselor(persisted.inquiry(), fa);
            return persisted.response();
        }
        throw new IllegalStateException("Unexpected agent result type: " + result.getClass());
    }

    // ---------- 1단계: 읽기 (짧은 트랜잭션) ----------

    private AnalysisContext loadContext(Long inquiryId) {
        return txTemplate.execute(status -> {
            Inquiry inquiry = inquiryRepository.findById(inquiryId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND",
                            "Inquiry not found"));

            if (isAlreadyFinished(inquiry)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE",
                        "이미 처리 완료된 문의입니다.");
            }

            List<InquiryMessage> history =
                    messageRepository.findByInquiryIdOrderByCreatedAtAsc(inquiryId);
            Long logId = analysisLogService.startRunning(inquiry);
            return new AnalysisContext(inquiry, history, logId, System.currentTimeMillis());
        });
    }

    // ---------- 2단계: 에이전트 실행 (트랜잭션 없음) ----------

    /**
     * 여기서 넘어가는 {@link Inquiry} 는 detached 다. {@code Inquiry} 는 지연 로딩 연관이
     * 하나도 없어(전부 {@code @Column}/{@code @Enumerated}) 안전하다.
     */
    private AgentResult runAgent(AnalysisContext ctx) {
        try {
            return agentService.run(ctx.inquiry(), ctx.history());
        } catch (ApiException ex) {
            analysisLogService.completeFailure(ctx.logId(), ex, ctx.startedAtMillis());
            throw ex;
        } catch (IllegalStateException ex) {
            analysisLogService.completeFailure(ctx.logId(), ex, ctx.startedAtMillis());
            log.error("AI 에이전트 파싱 실패 inquiryId={}", ctx.inquiry().getId(), ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_PARSE_ERROR",
                    "AI 응답을 파싱하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RuntimeException ex) {
            analysisLogService.completeFailure(ctx.logId(), ex, ctx.startedAtMillis());
            log.error("AI 에이전트 실패 inquiryId={}", ctx.inquiry().getId(), ex);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_ANALYSIS_ERROR",
                    "AI 분석 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    // ---------- 3단계: 결과 저장 (짧은 트랜잭션) ----------

    private PersistedFinalAnswer persistFinalAnswer(AnalysisContext ctx, AgentResult.FinalAnswer fa) {
        return txTemplate.execute(status -> {
            Inquiry inquiry = reloadForPersist(ctx.inquiry().getId());

            CategoryResultDto category = new CategoryResultDto(
                    fa.category(), fa.reason(), fa.needsHumanReview(), fa.needsEscalation(),
                    fa.fraudRiskFlag());
            UrgencyResultDto urgency = new UrgencyResultDto(fa.urgency(), fa.reason());
            DraftAnswerDto draft = new DraftAnswerDto(fa.answer(), "", List.of());

            inquiry.applyAnalysis(
                    InquiryCategory.valueOf(fa.category()),
                    UrgencyLevel.valueOf(fa.urgency()),
                    fa.answer()
            );

            if (!fa.needsEscalation() && !fa.needsHumanReview()) {
                inquiry.autoProcess(AUTO_PROCESSOR);
                log.info("[자동 처리] inquiryId={} category={} urgency={}",
                        inquiry.getId(), inquiry.getCategory(), inquiry.getUrgency());
            }
            inquiryRepository.save(inquiry);

            // AI 최종 답변 메시지 저장
            messageRepository.save(
                    InquiryMessage.of(inquiry.getId(), InquiryMessageRole.AI, fa.answer()));

            analysisLogService.completeSuccess(ctx.logId(), category, urgency, fa.retrievedChunks(),
                    draft, fa.steps(), ctx.startedAtMillis(), fa.totalTokens());

            InquiryAnalysisResponse response = InquiryAnalysisResponse.of(
                    inquiry, category, urgency, fa.retrievedChunks(), draft);
            return new PersistedFinalAnswer(response, inquiry);
        });
    }

    private InquiryAnalysisResponse persistFollowUp(AnalysisContext ctx,
            AgentResult.FollowUpQuestion fq) {
        return txTemplate.execute(status -> {
            Inquiry inquiry = reloadForPersist(ctx.inquiry().getId());
            inquiry.askFollowUp();
            inquiryRepository.save(inquiry);

            // AI 추가 질문 메시지 저장
            messageRepository.save(
                    InquiryMessage.of(inquiry.getId(), InquiryMessageRole.AI, fq.question()));
            log.info("[추가 질문] inquiryId={} question={}", inquiry.getId(), fq.question());

            analysisLogService.completeFollowUp(ctx.logId(), fq.question(), fq.steps(),
                    ctx.startedAtMillis(), fq.totalTokens());

            // 분석 결과는 아직 없으므로 빈 DTO 반환 (상태: PENDING_CUSTOMER)
            CategoryResultDto emptyCategory = new CategoryResultDto("GENERAL", "", false, false, false);
            UrgencyResultDto emptyUrgency = new UrgencyResultDto("LOW", "");
            DraftAnswerDto emptyDraft = new DraftAnswerDto(fq.question(), "", List.of());
            return InquiryAnalysisResponse.of(inquiry, emptyCategory, emptyUrgency, List.of(), emptyDraft);
        });
    }

    /**
     * 에이전트 실행 중(십수 초) 상담사가 문의를 종료했을 수 있다. 단일 트랜잭션이 공짜로 주던
     * 보호라서 경계를 나눈 뒤에는 직접 확인한다.
     */
    private Inquiry reloadForPersist(Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND",
                        "Inquiry not found"));
        if (isAlreadyFinished(inquiry)) {
            throw new ApiException(HttpStatus.CONFLICT, "INQUIRY_STATE_CHANGED",
                    "분석 중 문의 상태가 변경되어 결과를 저장하지 않았습니다.");
        }
        return inquiry;
    }

    private boolean isAlreadyFinished(Inquiry inquiry) {
        return inquiry.getStatus() == InquiryStatus.CLOSED
                || inquiry.getStatus() == InquiryStatus.AUTO_ANSWERED
                || inquiry.getStatus() == InquiryStatus.REVIEWED;
    }

    // ---------- 커밋 후: 외부 알림 ----------

    /**
     * 상담사 알림은 커밋 후에 보낸다. 트랜잭션 안에서 보내면 알림만 나가고 커밋이 실패하는
     * 순서가 생긴다.
     *
     * <p>{@code inquiry} 는 반드시 저장 단계가 돌려준 엔티티여야 한다.
     * {@code SlackCounselorNotificationService} 가 {@code getCategory()}/{@code getUrgency()}/
     * {@code getAiDraftAnswer()} 를 읽으므로, {@link AnalysisContext} 의 낡은 엔티티를 넘기면
     * 카테고리와 긴급도가 null 인 알림이 나간다.
     */
    private void notifyCounselor(Inquiry inquiry, AgentResult.FinalAnswer fa) {
        if (fa.needsEscalation()) {
            notificationService.notifyEscalationRequired(inquiry, fa.reason());
            notificationService.notifyHumanReviewRequired(inquiry, fa.reason());
        } else if (fa.needsHumanReview()) {
            notificationService.notifyHumanReviewRequired(inquiry, fa.reason());
        }
    }

    /**
     * 재시도 상한을 소진한 분석을 상담사에게 넘긴다.
     *
     * <p>ADR 0006 — 실패를 예외로 끝내지 않고 항상 상담사에게 도달시킨다. 이 조항이 없으면
     * 고객은 유저 포털에서 타이핑 버블을 영원히 본다({@code user/inquiry-detail.html} 은
     * {@code NEW} 면 무조건 typing 이고 폴링에 타임아웃이 없다).
     *
     * <p>합성 브리핑은 상담사용이라 고객 스레드에 메시지로 남기지 않는다 — 고객에게는
     * {@code AI_PROCESSED} 분기의 "상담사가 확인 후 답변드릴 예정입니다" 가 보인다.
     */
    public void escalateAfterRetriesExhausted(Long inquiryId, long failures, String lastError) {
        Inquiry persisted = txTemplate.execute(status -> {
            Inquiry inquiry = reloadForPersist(inquiryId);
            String briefing = """
                    [자동 합성] AI 분석이 %d회 실패해 상담사 검토로 올렸습니다.
                    마지막 오류: %s""".formatted(failures, lastError);
            inquiry.applyAnalysis(InquiryCategory.GENERAL, UrgencyLevel.MEDIUM, briefing);
            return inquiryRepository.save(inquiry);
        });

        notificationService.notifyHumanReviewRequired(persisted, "AI 분석 재시도 소진");
        log.error("[분석 포기] inquiryId={} 실패={}건 상담사 검토로 전환", inquiryId, failures);
    }

    private record AnalysisContext(
            Inquiry inquiry,
            List<InquiryMessage> history,
            Long logId,
            long startedAtMillis
    ) {}

    private record PersistedFinalAnswer(
            InquiryAnalysisResponse response,
            Inquiry inquiry
    ) {}
}
