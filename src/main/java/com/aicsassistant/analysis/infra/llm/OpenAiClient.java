package com.aicsassistant.analysis.infra.llm;

import com.aicsassistant.common.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class OpenAiClient implements LlmClient, EmbeddingClient {

    private final WebClient webClient;
    private final AiProperties aiProperties;

    public OpenAiClient(WebClient webClient, AiProperties aiProperties) {
        this.webClient = webClient;
        this.aiProperties = aiProperties;
    }

    @Override
    public String complete(String prompt) {
        JsonNode response = webClient.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + aiProperties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "model": "%s",
                          "messages": [
                            {"role": "user", "content": %s}
                          ],
                          "temperature": 0.1
                        }
                        """.formatted(aiProperties.getModel(), toJsonString(prompt)))
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
