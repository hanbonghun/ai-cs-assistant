package com.aicsassistant.analysis.dto;

public record CategoryResultDto(
        String value,
        String reason,
        boolean needsHumanReview,
        boolean needsEscalation,
        boolean medicalRiskFlag
) {
}
