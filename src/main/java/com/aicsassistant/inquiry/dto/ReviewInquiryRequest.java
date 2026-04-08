package com.aicsassistant.inquiry.dto;

import jakarta.validation.constraints.NotBlank;

public record ReviewInquiryRequest(
        @NotBlank String finalAnswer,
        String reviewMemo,
        @NotBlank String confirmedBy
) {
}
