package com.aicsassistant.analysis.dto;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import java.util.List;

public record InquiryAnalysisResponse(
        Long inquiryId,
        InquiryStatus status,
        boolean autoProcessed,
        boolean needsHumanReview,
        boolean needsEscalation,
        CategoryResultDto category,
        UrgencyResultDto urgency,
        List<RetrievedManualChunkDto> retrievedChunks,
        DraftAnswerDto draft
) {
    public static InquiryAnalysisResponse of(
            Inquiry inquiry,
            CategoryResultDto category,
            UrgencyResultDto urgency,
            List<RetrievedManualChunkDto> retrievedChunks,
            DraftAnswerDto draft
    ) {
        boolean autoProcessed = inquiry.getStatus() == InquiryStatus.AUTO_ANSWERED;
        return new InquiryAnalysisResponse(
                inquiry.getId(),
                inquiry.getStatus(),
                autoProcessed,
                category.needsHumanReview(),
                category.needsEscalation(),
                category,
                urgency,
                retrievedChunks,
                draft
        );
    }
}
