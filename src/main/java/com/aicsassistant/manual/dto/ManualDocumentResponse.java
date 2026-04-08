package com.aicsassistant.manual.dto;

import com.aicsassistant.inquiry.domain.InquiryCategory;
import java.time.LocalDateTime;

public record ManualDocumentResponse(
        Long id,
        String title,
        InquiryCategory category,
        String content,
        Integer version,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
