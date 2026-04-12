package com.aicsassistant.analysis.agent;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import java.util.List;

public record AgentStep(
        String thought,
        String action,
        String actionInput,
        String observation,
        List<RetrievedManualChunkDto> referencedChunks
) {
    public AgentStep(String thought, String action, String actionInput, String observation) {
        this(thought, action, actionInput, observation, List.of());
    }
}
