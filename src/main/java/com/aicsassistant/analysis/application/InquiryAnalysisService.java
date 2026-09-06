package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.agent.AgentResult;
import com.aicsassistant.analysis.agent.InquiryAgentService;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 문의 분석 유스케이스 — 순서만 정한다.
 *
 * <p>트랜잭션 경계는 {@link InquiryAnalysisRecorder} 가 갖고, 이 클래스는 그 사이에서 에이전트를
 * 돌린다. 그래서 여기에는 {@code @Transactional} 이 없다 — 에이전트가 LLM 을 십수 초 호출하는
 * 구간에 DB 커넥션을 잡지 않기 위해서다. 2026-09-04 에 커넥션을 14.2초 잡은 채 Railway Postgres
 * 절전으로 끊겨 문의가 유실된 사고가 있었다. 근거는 ADR 0009.
 *
 * <p>{@link #analyze} 가 그 사고의 수정을 그대로 보여주는 세 줄이다 — 읽기 / 에이전트 / 저장.
 * 이 메서드에 DB 작업이나 외부 호출을 직접 넣으면 경계가 다시 흐려진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryAnalysisService {

    private final InquiryAnalysisRecorder recorder;
    private final InquiryAgentService agentService;
    private final AnalysisLogService analysisLogService;
    private final CounselorNotificationService notificationService;

    public InquiryAnalysisResponse analyze(Long inquiryId) {
        AnalysisContext ctx = recorder.startAnalysis(inquiryId);
        AgentResult result = runAgent(ctx);

        if (result instanceof AgentResult.FollowUpQuestion followUp) {
            return recorder.recordFollowUp(ctx, followUp);
        }
        if (result instanceof AgentResult.FinalAnswer finalAnswer) {
            InquiryAnalysisRecorder.PersistedFinalAnswer persisted =
                    recorder.recordFinalAnswer(ctx, finalAnswer);
            notifyCounselor(persisted.inquiry(), finalAnswer);
            return persisted.response();
        }
        throw new IllegalStateException("Unexpected agent result type: " + result.getClass());
    }

    /**
     * 재시도 상한을 소진한 분석을 상담사에게 넘긴다.
     *
     * <p>ADR 0006 — 실패를 예외로 끝내지 않고 항상 상담사에게 도달시킨다. 이 조항이 없으면 고객은
     * 유저 포털에서 타이핑 버블을 영원히 본다({@code user/inquiry-detail.html} 은 {@code NEW} 면
     * 무조건 typing 이고 폴링에 타임아웃이 없다).
     */
    public void escalateAfterRetriesExhausted(Long inquiryId, long failures, String lastError) {
        Inquiry persisted = recorder.recordRetriesExhausted(inquiryId, failures, lastError);

        notificationService.notifyHumanReviewRequired(persisted, "AI 분석 재시도 소진");
        log.error("[분석 포기] inquiryId={} 실패={}건 상담사 검토로 전환", inquiryId, failures);
    }

    // ---------- 2단계: 에이전트 실행 (트랜잭션 없음) ----------

    /**
     * 에이전트를 돌리고 실패를 분석 로그에 남긴다.
     *
     * <p>실패 로그를 여기서 남기는 이유: 어떤 예외로 끝났는지는 이 호출을 감싸는 쪽만 안다.
     * {@link InquiryAnalysisRecorder} 는 성공 경로의 커밋 단위를 책임지고, 실패는 로그 한 줄이라
     * 트랜잭션을 묶을 것이 없다.
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

    // ---------- 커밋 후: 외부 알림 ----------

    /**
     * 상담사 알림은 커밋 후에 보낸다. 트랜잭션 안에서 보내면 알림만 나가고 커밋이 실패하는
     * 순서가 생긴다.
     *
     * <p>{@code inquiry} 는 반드시 저장 단계가 돌려준 엔티티여야 한다 — 이유는
     * {@link InquiryAnalysisRecorder.PersistedFinalAnswer} 참고.
     */
    private void notifyCounselor(Inquiry inquiry, AgentResult.FinalAnswer fa) {
        if (fa.needsEscalation()) {
            notificationService.notifyEscalationRequired(inquiry, fa.reason());
            notificationService.notifyHumanReviewRequired(inquiry, fa.reason());
        } else if (fa.needsHumanReview()) {
            notificationService.notifyHumanReviewRequired(inquiry, fa.reason());
        }
    }
}
