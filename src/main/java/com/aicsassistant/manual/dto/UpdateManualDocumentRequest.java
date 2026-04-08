package com.aicsassistant.manual.dto;

import com.aicsassistant.inquiry.domain.InquiryCategory;

public record UpdateManualDocumentRequest(
        String title,
        InquiryCategory category,
        String content
) {
}
