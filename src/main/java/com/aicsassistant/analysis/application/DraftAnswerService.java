package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.DraftAnswerDto;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.analysis.infra.llm.LlmClient;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DraftAnswerService {

    private final LlmClient llmClient;
    private final PromptFactory promptFactory;
    private final ObjectMapper objectMapper;

    public DraftAnswerService(LlmClient llmClient, PromptFactory promptFactory, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.promptFactory = promptFactory;
        this.objectMapper = objectMapper;
    }

    public DraftAnswerDto generate(
            Inquiry inquiry,
            CategoryResultDto category,
            UrgencyResultDto urgency,
            List<RetrievedManualChunkDto> retrievedChunks
    ) {
        String prompt = promptFactory.buildDraftPrompt(inquiry.getContent(), category, urgency, retrievedChunks);
        String response = llmClient.complete(prompt);
        JsonNode node = readJson(response);

        String answer = requiredText(node, "answer");
        String internalNote = node.path("internalNote").asText("");
        List<Long> usedChunkIds = readUsedChunkIds(node.path("usedChunkIds"));

        return new DraftAnswerDto(answer, internalNote, usedChunkIds);
    }

    private JsonNode readJson(String response) {
        try {
            return objectMapper.readTree(stripMarkdownFence(response));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse draft response", e);
        }
    }

    private String stripMarkdownFence(String response) {
        String trimmed = response.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    private String requiredText(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Draft response missing field: " + fieldName);
        }
        return value;
    }

    private List<Long> readUsedChunkIds(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.canConvertToLong()) {
                ids.add(element.longValue());
            }
        }
        return ids;
    }
}
