package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.analysis.infra.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class InquiryClassifier {

    private final LlmClient llmClient;
    private final PromptFactory promptFactory;
    private final ObjectMapper objectMapper;

    public InquiryClassifier(LlmClient llmClient, PromptFactory promptFactory, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.promptFactory = promptFactory;
        this.objectMapper = objectMapper;
    }

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
                node.path("medicalRiskFlag").asBoolean(false)
        );
        UrgencyResultDto urgency = new UrgencyResultDto(
                requiredText(node, "urgency"),
                reason
        );
        return new ClassificationResult(category, urgency, response);
    }

    private JsonNode readJson(String response) {
        try {
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse classification response", e);
        }
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
