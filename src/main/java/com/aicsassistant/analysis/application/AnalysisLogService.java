package com.aicsassistant.analysis.application;

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
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisLogService {

    private final InquiryAnalysisLogRepository inquiryAnalysisLogRepository;
    private final PromptFactory promptFactory;
    private final LlmClient llmClient;

    public AnalysisLogService(
            InquiryAnalysisLogRepository inquiryAnalysisLogRepository,
            PromptFactory promptFactory,
            LlmClient llmClient
    ) {
        this.inquiryAnalysisLogRepository = inquiryAnalysisLogRepository;
        this.promptFactory = promptFactory;
        this.llmClient = llmClient;
    }

    public void logSuccess(
            Inquiry inquiry,
            CategoryResultDto category,
            UrgencyResultDto urgency,
            List<RetrievedManualChunkDto> chunks,
            DraftAnswerDto draft,
            long startedAtMillis
    ) {
        InquiryAnalysisLog log = InquiryAnalysisLog.success(
                inquiry,
                inquiry.getContent(),
                InquiryCategory.valueOf(category.value()),
                UrgencyLevel.valueOf(urgency.value()),
                chunks.stream().map(RetrievedManualChunkDto::id).toList(),
                draft.answer(),
                llmClient.modelName(),
                promptFactory.promptVersion(),
                elapsed(startedAtMillis)
        );
        inquiryAnalysisLogRepository.save(log);
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

    private long elapsed(long startedAtMillis) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
    }
}
