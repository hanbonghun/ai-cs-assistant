package com.aicsassistant.inquiry.dto;

import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import java.time.LocalDateTime;
import java.util.List;

public record InquiryDetailResponse(
        Long id,
        String customerIdentifier,
        String title,
        String content,
        InquiryCategory category,
        UrgencyLevel urgency,
        InquiryStatus status,
        String aiDraftAnswer,
        String finalAnswer,
        String reviewMemo,
        String reviewedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<AnalysisLogSummary> analysisLogs
) {
    public static InquiryDetailResponse from(Inquiry inquiry, List<InquiryAnalysisLog> analysisLogs) {
        return new InquiryDetailResponse(
                inquiry.getId(),
                inquiry.getCustomerIdentifier(),
                inquiry.getTitle(),
                inquiry.getContent(),
                inquiry.getCategory(),
                inquiry.getUrgency(),
                inquiry.getStatus(),
                inquiry.getAiDraftAnswer(),
                inquiry.getFinalAnswer(),
                inquiry.getReviewMemo(),
                inquiry.getReviewedBy(),
                inquiry.getCreatedAt(),
                inquiry.getUpdatedAt(),
                analysisLogs.stream().map(AnalysisLogSummary::from).toList()
        );
    }

    public record AnalysisLogSummary(
            Long id,
            String status,
            InquiryCategory classifiedCategory,
            UrgencyLevel classifiedUrgency,
            String generatedDraft,
            String modelName,
            String promptVersion,
            Long latencyMs,
            LocalDateTime createdAt
    ) {
        public static AnalysisLogSummary from(InquiryAnalysisLog log) {
            return new AnalysisLogSummary(
                    log.getId(),
                    log.getAnalysisStatus().name(),
                    log.getClassifiedCategory(),
                    log.getClassifiedUrgency(),
                    log.getGeneratedDraft(),
                    log.getModelName(),
                    log.getPromptVersion(),
                    log.getLatencyMs(),
                    log.getCreatedAt()
            );
        }
    }
}
