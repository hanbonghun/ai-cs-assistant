package com.aicsassistant.inquiry.dto;

import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import jakarta.validation.constraints.NotBlank;

public record CreateInquiryRequest(
        @NotBlank String customerIdentifier,
        @NotBlank String title,
        @NotBlank String content,
        InquiryCategory category,
        UrgencyLevel urgency,
        String relatedOrderId
) {
}
