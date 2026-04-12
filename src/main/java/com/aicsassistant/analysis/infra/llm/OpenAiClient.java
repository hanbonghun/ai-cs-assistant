package com.aicsassistant.analysis.infra.llm;

import com.aicsassistant.common.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class OpenAiClient implements LlmClient, EmbeddingClient {

    private final WebClient webClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String complete(String prompt) {
        return complete(List.of(ChatMessage.user(prompt)));
    }

    @Override
    public String complete(List<ChatMessage> messages) {
        String messagesJson = serializeMessages(messages);
        JsonNode response = webClient.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + aiProperties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "model": "%s",
                          "messages": %s,
                          "temperature": 0.1
                        }
                        """.formatted(aiProperties.getModel(), messagesJson))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null) {
            throw new IllegalStateException("OpenAI chat completion returned empty response");
        }
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("OpenAI chat completion missing content");
        }
        return content.asText();
    }

    private String serializeMessages(List<ChatMessage> messages) {
        try {
            List<Map<String, String>> payload = messages.stream()
                    .map(m -> Map.of("role", m.role(), "content", m.content()))
                    .toList();
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize chat messages", e);
        }
    }

    @Override
    public List<Double> embed(String text) {
        JsonNode response = webClient.post()
                .uri("https://api.openai.com/v1/embeddings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + aiProperties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "model": "%s",
                          "input": %s
                        }
                        """.formatted(aiProperties.getEmbeddingModel(), toJsonString(text)))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null) {
            throw new IllegalStateException("OpenAI embedding returned empty response");
        }

        JsonNode vectorNode = response.path("data").path(0).path("embedding");
        if (!vectorNode.isArray()) {
            throw new IllegalStateException("OpenAI embedding missing vector");
        }

        List<Double> vector = new ArrayList<>();
        for (JsonNode dimension : vectorNode) {
            vector.add(dimension.asDouble());
        }
        return vector;
    }

    @Override
    public String modelName() {
        return aiProperties.getModel();
    }

    private String toJsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
