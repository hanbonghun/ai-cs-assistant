package com.aicsassistant.ui.viewmodel;

import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import java.util.List;

public record InquiryDetailViewModel(
        InquiryDetailResponse inquiry,
        List<EvidenceChunkView> evidenceChunks
) {
    public static InquiryDetailViewModel from(
            InquiryDetailResponse inquiry,
            List<EvidenceChunkView> evidenceChunks
    ) {
        return new InquiryDetailViewModel(inquiry, List.copyOf(evidenceChunks));
    }

    public record EvidenceChunkView(
            Long id,
            Long manualDocumentId,
            String manualDocumentTitle,
            String manualCategory,
            Integer chunkIndex,
            Integer documentVersion,
            Integer tokenCount,
            String content
    ) {
    }
}
