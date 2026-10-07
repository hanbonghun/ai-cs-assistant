package com.aicsassistant.analysis.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RateDraftRequest(
        @NotNull @Pattern(regexp = "GOOD|BAD") String rating,
        String reason,
        String note
) {
}
