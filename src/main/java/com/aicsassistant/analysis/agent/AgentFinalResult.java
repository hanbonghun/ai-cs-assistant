package com.aicsassistant.analysis.agent;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import java.util.List;

public record AgentFinalResult(
        String finalAnswer,
        String category,
        String urgency,
        boolean needsHumanReview,
        boolean needsEscalation,
        boolean fraudRiskFlag,
        String reason,
        List<AgentStep> steps,
        List<RetrievedManualChunkDto> retrievedChunks
) {}
