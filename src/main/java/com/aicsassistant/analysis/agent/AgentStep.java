package com.aicsassistant.analysis.agent;

public record AgentStep(
        String thought,
        String action,
        String actionInput,
        String observation
) {}
