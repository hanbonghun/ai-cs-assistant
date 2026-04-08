package com.aicsassistant.inquiry.dto;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import java.time.LocalDateTime;

public record InquiryListResponse(
        Long id,
        String customerIdentifier,
        String title,
        InquiryCategory category,
        UrgencyLevel urgency,
        InquiryStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static InquiryListResponse from(Inquiry inquiry) {
        return new InquiryListResponse(
                inquiry.getId(),
                inquiry.getCustomerIdentifier(),
                inquiry.getTitle(),
                inquiry.getCategory(),
                inquiry.getUrgency(),
                inquiry.getStatus(),
                inquiry.getCreatedAt(),
                inquiry.getUpdatedAt()
        );
    }
}
