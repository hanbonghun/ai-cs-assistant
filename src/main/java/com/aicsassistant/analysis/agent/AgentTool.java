package com.aicsassistant.analysis.agent;

import com.fasterxml.jackson.databind.JsonNode;

public interface AgentTool {

    String name();

    /** One-line description shown to the LLM in the system prompt. */
    String description();

    ToolResult execute(JsonNode input);
}
