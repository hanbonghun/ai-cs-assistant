package com.aicsassistant.manual.dto;

import com.aicsassistant.inquiry.domain.InquiryCategory;

public record CreateManualDocumentRequest(
        String title,
        InquiryCategory category,
        String content
) {
}
