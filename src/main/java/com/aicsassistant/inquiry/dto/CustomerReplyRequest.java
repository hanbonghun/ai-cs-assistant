package com.aicsassistant.inquiry.dto;

import jakarta.validation.constraints.NotBlank;

public record CustomerReplyRequest(
        @NotBlank String content
) {
}
