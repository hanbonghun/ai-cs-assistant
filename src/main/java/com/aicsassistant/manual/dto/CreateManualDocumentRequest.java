package com.aicsassistant.manual.dto;

import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.manual.domain.ManualVisibility;

public record CreateManualDocumentRequest(
        String title,
        InquiryCategory category,
        String content,
        ManualVisibility visibility
) {

    /** 공개 범위를 주지 않으면 고객용이다. 기존 API 호출자는 필드를 모르므로 null 로 들어온다. */
    public CreateManualDocumentRequest {
        if (visibility == null) {
            visibility = ManualVisibility.CUSTOMER;
        }
    }

    public CreateManualDocumentRequest(String title, InquiryCategory category, String content) {
        this(title, category, content, ManualVisibility.CUSTOMER);
    }
}
