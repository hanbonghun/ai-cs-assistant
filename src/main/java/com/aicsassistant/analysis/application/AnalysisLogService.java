package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.agent.AgentStep;
import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.DraftAnswerDto;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.analysis.infra.llm.LlmClient;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisLogService {

    private final InquiryAnalysisLogRepository inquiryAnalysisLogRepository;
    private final PromptFactory promptFactory;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public void logSuccess(
            Inquiry inquiry,
            CategoryResultDto category,
            UrgencyResultDto urgency,
            List<RetrievedManualChunkDto> chunks,
            DraftAnswerDto draft,
            List<AgentStep> agentSteps,
            long startedAtMillis,
            int totalTokens
    ) {
        InquiryAnalysisLog logEntry = InquiryAnalysisLog.success(
                inquiry,
                inquiry.getContent(),
                InquiryCategory.valueOf(category.value()),
                UrgencyLevel.valueOf(urgency.value()),
                chunks.stream().map(RetrievedManualChunkDto::id).toList(),
                draft.answer(),
                llmClient.modelName(),
                promptFactory.promptVersion(),
                serializeSteps(agentSteps),
                elapsed(startedAtMillis),
                totalTokens
        );
        inquiryAnalysisLogRepository.save(logEntry);
    }

    private String serializeSteps(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception e) {
            log.warn("에이전트 스텝 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailure(
            Inquiry inquiry,
            RuntimeException exception,
            long startedAtMillis
    ) {
        InquiryAnalysisLog log = InquiryAnalysisLog.failure(
                inquiry,
                inquiry.getContent(),
                llmClient.modelName(),
                promptFactory.promptVersion(),
                exception.getMessage(),
                elapsed(startedAtMillis)
        );
        inquiryAnalysisLogRepository.save(log);
    }

    @Transactional
    public void rateLatestLog(Long inquiryId, String rating) {
        inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId)
                .stream()
                .findFirst()
                .ifPresent(logEntry -> {
                    logEntry.rate(rating);
                    inquiryAnalysisLogRepository.save(logEntry);
                });
    }

    private long elapsed(long startedAtMillis) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
    }
}
