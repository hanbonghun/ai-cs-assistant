package com.aicsassistant.inquiry.dto;

import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import java.time.LocalDateTime;

public record InquiryMessageResponse(
        InquiryMessageRole role,
        String content,
        LocalDateTime createdAt
) {
    public static InquiryMessageResponse from(InquiryMessage message) {
        return new InquiryMessageResponse(message.getRole(), message.getContent(), message.getCreatedAt());
    }
}
