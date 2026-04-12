package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.analysis.infra.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InquiryClassifier {

    private final LlmClient llmClient;
    private final PromptFactory promptFactory;
    private final ObjectMapper objectMapper;

    public ClassificationResult classify(String inquiryContent) {
        String prompt = promptFactory.buildClassificationPrompt(inquiryContent);
        String response = llmClient.complete(prompt);
        JsonNode node = readJson(response);

        String reason = node.path("reason").asText("");
        CategoryResultDto category = new CategoryResultDto(
                requiredText(node, "category"),
                reason,
                node.path("needsHumanReview").asBoolean(false),
                node.path("needsEscalation").asBoolean(false),
                node.path("fraudRiskFlag").asBoolean(false)
        );
        UrgencyResultDto urgency = new UrgencyResultDto(
                requiredText(node, "urgency"),
                reason
        );
        return new ClassificationResult(category, urgency, response);
    }

    private JsonNode readJson(String response) {
        try {
            return objectMapper.readTree(stripMarkdownFence(response));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse classification response", e);
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
            throw new IllegalStateException("Classification response missing field: " + fieldName);
        }
        return value;
    }

    public record ClassificationResult(
            CategoryResultDto category,
            UrgencyResultDto urgency,
            String rawResponse
    ) {
    }
}
