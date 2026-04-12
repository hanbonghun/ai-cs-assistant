package com.aicsassistant.ui.viewmodel;

import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import java.time.LocalDateTime;
import java.util.List;

public record InquiryDetailViewModel(
        InquiryDetailResponse inquiry,
        List<EvidenceChunkView> evidenceChunks,
        List<MessageView> messages
) {
    public static InquiryDetailViewModel from(
            InquiryDetailResponse inquiry,
            List<EvidenceChunkView> evidenceChunks,
            List<InquiryMessage> messages
    ) {
        List<MessageView> messageViews = messages.stream()
                .map(m -> new MessageView(m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();
        return new InquiryDetailViewModel(inquiry, List.copyOf(evidenceChunks), messageViews);
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
    ) {}

    public record MessageView(
            InquiryMessageRole role,
            String content,
            LocalDateTime createdAt
    ) {
        public boolean isAi() { return role == InquiryMessageRole.AI; }
        public boolean isCustomer() { return role == InquiryMessageRole.CUSTOMER; }
    }
}
