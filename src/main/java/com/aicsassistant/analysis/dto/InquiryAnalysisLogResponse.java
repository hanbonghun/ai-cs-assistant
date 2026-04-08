package com.aicsassistant.analysis.dto;

import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import java.time.LocalDateTime;

public record InquiryAnalysisLogResponse(
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
    public static InquiryAnalysisLogResponse from(InquiryAnalysisLog log) {
        return new InquiryAnalysisLogResponse(
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
