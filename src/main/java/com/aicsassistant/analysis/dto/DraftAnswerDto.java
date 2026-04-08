package com.aicsassistant.analysis.dto;

import java.util.List;

public record DraftAnswerDto(
        String answer,
        String internalNote,
        List<Long> usedChunkIds
) {
}
