package com.aicsassistant.analysis.agent.tool;

import com.aicsassistant.analysis.agent.AgentTool;
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
    public String execute(JsonNode input) {
        String query = input.path("query").asText("").strip();
        if (query.isBlank()) {
            return "Error: 'query' field is required.";
        }

        List<RetrievedManualChunkDto> chunks = manualRetrievalService.retrieve(query);
        collectedChunks.addAll(chunks);

        if (chunks.isEmpty()) {
            return "No relevant policy documents found for this query.";
        }

        return chunks.stream()
                .map(c -> "[%s / %s]\n%s".formatted(
                        c.manualDocumentTitle(), c.manualCategory(), c.content()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /** Returns all chunks retrieved across every call during this agent run. */
    public List<RetrievedManualChunkDto> getCollectedChunks() {
        return List.copyOf(collectedChunks);
    }
}
