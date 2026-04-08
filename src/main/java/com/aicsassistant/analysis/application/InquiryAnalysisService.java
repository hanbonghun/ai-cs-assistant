package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.DraftAnswerDto;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.common.exception.ApiException;
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

    private final InquiryRepository inquiryRepository;
    private final InquiryClassifier inquiryClassifier;
    private final UrgencyClassifier urgencyClassifier;
    private final ManualRetrievalService manualRetrievalService;
    private final DraftAnswerService draftAnswerService;
    private final AnalysisLogService analysisLogService;

    public InquiryAnalysisService(
            InquiryRepository inquiryRepository,
            InquiryClassifier inquiryClassifier,
            UrgencyClassifier urgencyClassifier,
            ManualRetrievalService manualRetrievalService,
            DraftAnswerService draftAnswerService,
            AnalysisLogService analysisLogService
    ) {
        this.inquiryRepository = inquiryRepository;
        this.inquiryClassifier = inquiryClassifier;
        this.urgencyClassifier = urgencyClassifier;
        this.manualRetrievalService = manualRetrievalService;
        this.draftAnswerService = draftAnswerService;
        this.analysisLogService = analysisLogService;
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
            InquiryClassifier.ClassificationResult classification = inquiryClassifier.classify(inquiry.getContent());
            CategoryResultDto category = classification.category();
            UrgencyResultDto urgency = urgencyClassifier.classify(classification);
            List<RetrievedManualChunkDto> chunks = manualRetrievalService.retrieve(inquiry.getContent());
            DraftAnswerDto draft = draftAnswerService.generate(inquiry, category, urgency, chunks);

            inquiry.applyAnalysis(
                    InquiryCategory.valueOf(category.value()),
                    UrgencyLevel.valueOf(urgency.value()),
                    draft.answer()
            );
            inquiryRepository.save(inquiry);

            analysisLogService.logSuccess(inquiry, category, urgency, chunks, draft, startedAtMillis);
            return InquiryAnalysisResponse.of(inquiry, category, urgency, chunks, draft);
        } catch (RuntimeException ex) {
            analysisLogService.logFailure(inquiry, ex, startedAtMillis);
            throw ex;
        }
    }
}
