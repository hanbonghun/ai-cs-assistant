package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.agent.AgentResult;
import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.DraftAnswerDto;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 잡의 시작과 끝을 DB 에 기록한다 — 문의 상태·대화 메시지·분석 로그를 한 트랜잭션으로 묶는다.
 *
 * <p>{@link InquiryAnalysisService} 에서 분리한 이유는 두 가지다.
 * <ol>
 *   <li>역할이 다르다. 서비스는 "무엇을 어떤 순서로" 를 정하고, 여기는 "무엇을 함께 커밋하는가" 만
 *       안다. 분리 전에는 세 줄짜리 유스케이스가 이백 줄의 영속화 코드에 묻혀 ADR 0009 가 만든
 *       3단계 구조가 코드에서 보이지 않았다</li>
 *   <li>별개 빈이라 {@code @Transactional} 프록시가 다시 돈다. ADR 0009 는 자기 호출이 프록시를
 *       타지 않는 문제 때문에 {@code TransactionTemplate} 을 골랐고 "여기만 경계가 호출 지점에서
 *       안 보인다" 를 대가로 안고 있었다. 프록시 사정이 아니라 응집도 때문에 클래스를 나누면
 *       그 대가가 사라진다</li>
 * </ol>
 *
 * <p><b>이 클래스의 public 메서드는 서로를 호출하지 않는다.</b> 같은 빈 안의 호출은 프록시를 타지
 * 않아 트랜잭션이 조용히 안 걸린다 — ADR 0009 가 피하려던 바로 그 함정이다.
 *
 * <p>모든 메서드는 짧아야 한다. 여기에 외부 호출(LLM·Slack)을 넣으면 사고가 되돌아온다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryAnalysisRecorder {

    private static final String AUTO_PROCESSOR = "ai-auto";

    private final InquiryRepository inquiryRepository;
    private final InquiryMessageRepository messageRepository;
    private final AnalysisLogService analysisLogService;

    /**
     * 1단계 — 읽기. 분석 대상과 대화 히스토리를 읽고 분석 로그를 {@code RUNNING} 으로 연다.
     *
     * <p>이미 진행 중인 분석이 있으면 {@link AnalysisLogService#startRunning} 이 거부한다 —
     * 수동 버튼과 재시도 스위퍼가 겹치는 것을 막는다.
     */
    @Transactional
    public AnalysisContext startAnalysis(Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND",
                        "Inquiry not found"));

        if (inquiry.getStatus().isFinished()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE",
                    "이미 처리 완료된 문의입니다.");
        }

        List<InquiryMessage> history =
                messageRepository.findByInquiryIdOrderByCreatedAtAsc(inquiryId);
        Long logId = analysisLogService.startRunning(inquiry);
        return new AnalysisContext(inquiry, history, logId, System.currentTimeMillis());
    }

    /**
     * 3단계 — 최종 답변 기록. 분류·초안·상태 전이·AI 메시지·분석 로그가 한 커밋에 들어간다.
     *
     * <p>상담사 검토가 필요 없는 건만 {@code AUTO_ANSWERED} 로 확정한다.
     */
    @Transactional
    public PersistedFinalAnswer recordFinalAnswer(AnalysisContext ctx, AgentResult.FinalAnswer fa) {
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

        messageRepository.save(
                InquiryMessage.of(inquiry.getId(), InquiryMessageRole.AI, fa.answer()));

        analysisLogService.completeSuccess(ctx.logId(), category, urgency, fa.retrievedChunks(),
                draft, fa.steps(), ctx.startedAtMillis(), fa.totalTokens());

        InquiryAnalysisResponse response = InquiryAnalysisResponse.of(
                inquiry, category, urgency, fa.retrievedChunks(), draft);
        return new PersistedFinalAnswer(response, inquiry);
    }

    /**
     * 3단계 — 추가 질문 기록. 분류 결과가 아직 없으므로 빈 DTO 로 응답하고 상태는
     * {@code PENDING_CUSTOMER} 로 둔다.
     */
    @Transactional
    public InquiryAnalysisResponse recordFollowUp(AnalysisContext ctx, AgentResult.FollowUpQuestion fq) {
        Inquiry inquiry = reloadForPersist(ctx.inquiry().getId());
        inquiry.askFollowUp();
        inquiryRepository.save(inquiry);

        messageRepository.save(
                InquiryMessage.of(inquiry.getId(), InquiryMessageRole.AI, fq.question()));
        log.info("[추가 질문] inquiryId={} question={}", inquiry.getId(), fq.question());

        analysisLogService.completeFollowUp(ctx.logId(), fq.question(), fq.steps(),
                ctx.startedAtMillis(), fq.totalTokens());

        CategoryResultDto emptyCategory = new CategoryResultDto("GENERAL", "", false, false, false);
        UrgencyResultDto emptyUrgency = new UrgencyResultDto("LOW", "");
        DraftAnswerDto emptyDraft = new DraftAnswerDto(fq.question(), "", List.of());
        return InquiryAnalysisResponse.of(inquiry, emptyCategory, emptyUrgency, List.of(), emptyDraft);
    }

    /**
     * 재시도 상한을 소진한 분석을 상담사 검토로 올린다 (ADR 0006).
     *
     * <p>합성 브리핑은 상담사용이라 고객 스레드에 메시지로 남기지 않는다 — 고객에게는
     * {@code AI_PROCESSED} 분기의 "상담사가 확인 후 답변드릴 예정입니다" 가 보인다.
     *
     * <p>알림은 호출자가 커밋 후에 보낸다. 반환한 엔티티를 그대로 넘겨야 카테고리·긴급도가
     * 채워진 알림이 나간다.
     */
    @Transactional
    public Inquiry recordRetriesExhausted(Long inquiryId, long failures, String lastError) {
        Inquiry inquiry = reloadForPersist(inquiryId);
        String briefing = """
                [자동 합성] AI 분석이 %d회 실패해 상담사 검토로 올렸습니다.
                마지막 오류: %s""".formatted(failures, lastError);
        inquiry.applyAnalysis(InquiryCategory.GENERAL, UrgencyLevel.MEDIUM, briefing);
        return inquiryRepository.save(inquiry);
    }

    /**
     * 에이전트 실행 중(십수 초) 상담사가 문의를 종료했을 수 있다. 단일 트랜잭션이 공짜로 주던
     * 보호라서 경계를 나눈 뒤에는 직접 확인한다.
     */
    private Inquiry reloadForPersist(Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND",
                        "Inquiry not found"));
        if (inquiry.getStatus().isFinished()) {
            throw new ApiException(HttpStatus.CONFLICT, "INQUIRY_STATE_CHANGED",
                    "분석 중 문의 상태가 변경되어 결과를 저장하지 않았습니다.");
        }
        return inquiry;
    }

    /**
     * 저장된 응답과 저장 시점의 엔티티.
     *
     * <p>엔티티를 함께 돌려주는 이유: 상담사 알림이 {@code getCategory()}/{@code getUrgency()}/
     * {@code getAiDraftAnswer()} 를 읽는다. {@link AnalysisContext} 의 낡은 엔티티를 넘기면
     * 카테고리와 긴급도가 null 인 알림이 나간다.
     */
    public record PersistedFinalAnswer(
            InquiryAnalysisResponse response,
            Inquiry inquiry
    ) {
    }
}
