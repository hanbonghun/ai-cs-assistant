package com.aicsassistant.inquiry.dto;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import java.time.LocalDateTime;

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
        String relatedOrderId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static InquiryDetailResponse from(Inquiry inquiry) {
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
                inquiry.getRelatedOrderId(),
                inquiry.getCreatedAt(),
                inquiry.getUpdatedAt()
        );
    }
}
