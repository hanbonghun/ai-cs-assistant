package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.agent.AgentFinalResult;
import com.aicsassistant.analysis.agent.InquiryAgentService;
import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.DraftAnswerDto;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InquiryAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(InquiryAnalysisService.class);
    private static final String AUTO_PROCESSOR = "ai-auto";

    private final InquiryRepository inquiryRepository;
    private final InquiryAgentService agentService;
    private final AnalysisLogService analysisLogService;
    private final CounselorNotificationService notificationService;

    public InquiryAnalysisService(
            InquiryRepository inquiryRepository,
            InquiryAgentService agentService,
            AnalysisLogService analysisLogService,
            CounselorNotificationService notificationService
    ) {
        this.inquiryRepository = inquiryRepository;
        this.agentService = agentService;
        this.analysisLogService = analysisLogService;
        this.notificationService = notificationService;
    }

    @Transactional
    public InquiryAnalysisResponse analyze(Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Inquiry not found"));

        if (inquiry.getStatus() == InquiryStatus.CLOSED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE", "Closed inquiry cannot be analyzed");
        }

        long startedAtMillis = System.currentTimeMillis();

        try {
            AgentFinalResult result = agentService.run(inquiry);

            CategoryResultDto category = new CategoryResultDto(
                    result.category(),
                    result.reason(),
                    result.needsHumanReview(),
                    result.needsEscalation(),
                    result.fraudRiskFlag()
            );
            UrgencyResultDto urgency = new UrgencyResultDto(result.urgency(), result.reason());
            DraftAnswerDto draft = new DraftAnswerDto(result.finalAnswer(), "", List.of());

            inquiry.applyAnalysis(
                    InquiryCategory.valueOf(result.category()),
                    UrgencyLevel.valueOf(result.urgency()),
                    result.finalAnswer()
            );

            route(inquiry, category);
            inquiryRepository.save(inquiry);

            analysisLogService.logSuccess(inquiry, category, urgency, result.retrievedChunks(), draft, startedAtMillis);
            return InquiryAnalysisResponse.of(inquiry, category, urgency, result.retrievedChunks(), draft);

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
