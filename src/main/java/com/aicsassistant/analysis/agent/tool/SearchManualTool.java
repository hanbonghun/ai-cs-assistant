package com.aicsassistant.analysis.agent.tool;

import com.aicsassistant.analysis.agent.AgentTool;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.analysis.application.ManualRetrievalService;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent tool that performs a RAG lookup against the policy/manual vector store.
 * Not a Spring bean — instantiated fresh per agent run to avoid shared mutable state.
 */
public class SearchManualTool implements AgentTool {

    private final ManualRetrievalService manualRetrievalService;
    private final List<RetrievedManualChunkDto> collectedChunks = new ArrayList<>();
    private List<RetrievedManualChunkDto> lastCallChunks = List.of();

    public SearchManualTool(ManualRetrievalService manualRetrievalService) {
        this.manualRetrievalService = manualRetrievalService;
    }

    @Override
    public String name() {
        return "search_manual";
    }

    @Override
    public String description() {
        return "search_manual(query: string) — Searches the policy/manual knowledge base. "
                + "Call this before answering any policy question.";
    }

    @Override
    public ToolResult execute(JsonNode input) {
        String query = input.path("query").asText("").strip();
        if (query.isBlank()) {
            return ToolResult.error(
                    ToolErrorCategory.VALIDATION,
                    false,
                    "'query' field is required.");
        }

        List<RetrievedManualChunkDto> chunks = manualRetrievalService.retrieve(query);
        lastCallChunks = List.copyOf(chunks);
        collectedChunks.addAll(chunks);

        if (chunks.isEmpty()) {
            return ToolResult.success("No relevant policy documents found for this query.");
        }

        String formatted = chunks.stream()
                .map(c -> "[%s / %s]\n%s".formatted(
                        c.manualDocumentTitle(), c.manualCategory(), c.content()))
                .collect(Collectors.joining("\n\n---\n\n"));
        return ToolResult.success(formatted);
    }

    /** Returns all chunks retrieved across every call during this agent run. */
    public List<RetrievedManualChunkDto> getCollectedChunks() {
        return List.copyOf(collectedChunks);
    }

    /** Returns only the chunks retrieved in the most recent call. */
    public List<RetrievedManualChunkDto> getLastCallChunks() {
        return lastCallChunks;
    }
}
