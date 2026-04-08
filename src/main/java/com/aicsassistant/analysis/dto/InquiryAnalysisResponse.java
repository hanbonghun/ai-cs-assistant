package com.aicsassistant.analysis.dto;

import com.aicsassistant.inquiry.domain.Inquiry;
import java.util.List;

public record InquiryAnalysisResponse(
        Long inquiryId,
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
        return new InquiryAnalysisResponse(inquiry.getId(), category, urgency, retrievedChunks, draft);
    }
}
