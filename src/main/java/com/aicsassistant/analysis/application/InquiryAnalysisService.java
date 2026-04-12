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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InquiryAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(InquiryAnalysisService.class);
    private static final String AUTO_PROCESSOR = "ai-auto";

    private final InquiryRepository inquiryRepository;
    private final InquiryMessageRepository messageRepository;
    private final InquiryAgentService agentService;
    private final AnalysisLogService analysisLogService;
    private final CounselorNotificationService notificationService;

    public InquiryAnalysisService(
            InquiryRepository inquiryRepository,
            InquiryMessageRepository messageRepository,
            InquiryAgentService agentService,
            AnalysisLogService analysisLogService,
            CounselorNotificationService notificationService
    ) {
        this.inquiryRepository = inquiryRepository;
        this.messageRepository = messageRepository;
        this.agentService = agentService;
        this.analysisLogService = analysisLogService;
        this.notificationService = notificationService;
    }

    @Transactional
    public InquiryAnalysisResponse analyze(Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Inquiry not found"));

        if (inquiry.getStatus() == InquiryStatus.CLOSED
                || inquiry.getStatus() == InquiryStatus.AUTO_ANSWERED
                || inquiry.getStatus() == InquiryStatus.REVIEWED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE",
                    "이미 처리 완료된 문의입니다.");
        }

        long startedAtMillis = System.currentTimeMillis();
        List<InquiryMessage> history = messageRepository.findByInquiryIdOrderByCreatedAtAsc(inquiryId);

        try {
            AgentResult result = agentService.run(inquiry, history);

            if (result instanceof AgentResult.FinalAnswer fa) {
                return handleFinalAnswer(inquiry, fa, startedAtMillis);
            } else if (result instanceof AgentResult.FollowUpQuestion fq) {
                return handleFollowUp(inquiry, fq, startedAtMillis);
            } else {
                throw new IllegalStateException("Unexpected agent result type: " + result.getClass());
            }

        } catch (ApiException ex) {
            analysisLogService.logFailure(inquiry, ex, startedAtMillis);
            throw ex;
        } catch (IllegalStateException ex) {
            analysisLogService.logFailure(inquiry, ex, startedAtMillis);
            log.error("AI 에이전트 파싱 실패 inquiryId={}", inquiryId, ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_PARSE_ERROR",
                    "AI 응답을 파싱하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RuntimeException ex) {
            analysisLogService.logFailure(inquiry, ex, startedAtMillis);
            log.error("AI 에이전트 실패 inquiryId={}", inquiryId, ex);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_ANALYSIS_ERROR",
                    "AI 분석 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private InquiryAnalysisResponse handleFinalAnswer(
            Inquiry inquiry, AgentResult.FinalAnswer fa, long startedAtMillis) {

        CategoryResultDto category = new CategoryResultDto(
                fa.category(), fa.reason(), fa.needsHumanReview(), fa.needsEscalation(), fa.fraudRiskFlag());
        UrgencyResultDto urgency = new UrgencyResultDto(fa.urgency(), fa.reason());
        DraftAnswerDto draft = new DraftAnswerDto(fa.answer(), "", List.of());

        inquiry.applyAnalysis(
                InquiryCategory.valueOf(fa.category()),
                UrgencyLevel.valueOf(fa.urgency()),
                fa.answer()
        );

        route(inquiry, category);
        inquiryRepository.save(inquiry);

        // AI 최종 답변 메시지 저장
        messageRepository.save(InquiryMessage.of(inquiry.getId(), InquiryMessageRole.AI, fa.answer()));

        analysisLogService.logSuccess(inquiry, category, urgency, fa.retrievedChunks(), draft, startedAtMillis);
        return InquiryAnalysisResponse.of(inquiry, category, urgency, fa.retrievedChunks(), draft);
    }

    private InquiryAnalysisResponse handleFollowUp(
            Inquiry inquiry, AgentResult.FollowUpQuestion fq, long startedAtMillis) {

        inquiry.askFollowUp();
        inquiryRepository.save(inquiry);

        // AI 추가 질문 메시지 저장
        messageRepository.save(InquiryMessage.of(inquiry.getId(), InquiryMessageRole.AI, fq.question()));

        log.info("[추가 질문] inquiryId={} question={}", inquiry.getId(), fq.question());

        // 분석 결과는 아직 없으므로 빈 DTO 반환 (상태: PENDING_CUSTOMER)
        CategoryResultDto emptyCategory = new CategoryResultDto("GENERAL", "", false, false, false);
        UrgencyResultDto emptyUrgency = new UrgencyResultDto("LOW", "");
        DraftAnswerDto emptyDraft = new DraftAnswerDto(fq.question(), "", List.of());

        return InquiryAnalysisResponse.of(inquiry, emptyCategory, emptyUrgency, List.of(), emptyDraft);
    }

    private void route(Inquiry inquiry, CategoryResultDto category) {
        if (category.needsEscalation()) {
            notificationService.notifyEscalationRequired(inquiry, category.reason());
            notificationService.notifyHumanReviewRequired(inquiry, category.reason());
        } else if (category.needsHumanReview()) {
            notificationService.notifyHumanReviewRequired(inquiry, category.reason());
        } else {
            inquiry.autoProcess(AUTO_PROCESSOR);
            log.info("[자동 처리] inquiryId={} category={} urgency={}",
                    inquiry.getId(), inquiry.getCategory(), inquiry.getUrgency());
        }
    }
}
