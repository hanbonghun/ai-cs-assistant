package com.aicsassistant.analysis.infra.llm;

import com.aicsassistant.common.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
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

    private static final AttributeKey<String> ATTR_LF_TYPE = AttributeKey.stringKey("langfuse.observation.type");
    private static final AttributeKey<String> ATTR_LF_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    private static final AttributeKey<String> ATTR_LF_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    private static final AttributeKey<String> ATTR_GENAI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> ATTR_GENAI_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    // OTel GenAI semconv는 prompt/completion(구) → input/output(신)으로 이동 중. 양쪽 다 세팅해 호환성 확보.
    private static final AttributeKey<Long> ATTR_GENAI_PROMPT_TOKENS = AttributeKey.longKey("gen_ai.usage.prompt_tokens");
    private static final AttributeKey<Long> ATTR_GENAI_COMPLETION_TOKENS = AttributeKey.longKey("gen_ai.usage.completion_tokens");
    private static final AttributeKey<Long> ATTR_GENAI_INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> ATTR_GENAI_OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<Long> ATTR_GENAI_TOTAL_TOKENS = AttributeKey.longKey("gen_ai.usage.total_tokens");
    private static final AttributeKey<Long> ATTR_GENAI_CACHE_READ_TOKENS =
            AttributeKey.longKey("gen_ai.usage.cache_read_input_tokens");

    private final WebClient webClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    @Override
    public String complete(String prompt) {
        return complete(List.of(ChatMessage.user(prompt)));
    }

    @Override
    public String complete(List<ChatMessage> messages) {
        return completeWithUsage(messages).content();
    }

    @Override
    public LlmResponse completeWithUsage(List<ChatMessage> messages) {
        String messagesJson = serializeMessages(messages);
        Span span = tracer.spanBuilder("openai.chat.completion")
                .setAttribute(ATTR_LF_TYPE, "generation")
                .setAttribute(ATTR_GENAI_SYSTEM, "openai")
                .setAttribute(ATTR_GENAI_MODEL, aiProperties.getModel())
                .setAttribute(ATTR_LF_INPUT, messagesJson)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
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
            int promptTokens = response.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = response.path("usage").path("completion_tokens").asInt(0);
            // promptTokens 중 캐시에서 재사용된 몫. OpenAI 는 접두부가 일정 길이를 넘으면 자동으로
            // 캐시한다 — 0 이 계속 나오면 캐시가 안 걸리는 것이고, 원인은 대개 프롬프트 접두부가
            // 요청마다 달라지는 것이거나 접두부가 최소 길이에 못 미치는 것이다.
            //
            // 경로를 두 개 보는 이유: Chat Completions 는 usage 를 prompt_tokens 계열로 부르고
            // (prompt_tokens_details), 신형 Responses API 문서는 input_tokens 계열을 쓴다
            // (input_tokens_details). 어느 쪽이 오든 읽히게 두면 필드명을 추측하지 않아도 된다.
            JsonNode usage = response.path("usage");
            int cacheReadTokens = usage.path("prompt_tokens_details").path("cached_tokens")
                    .asInt(usage.path("input_tokens_details").path("cached_tokens").asInt(0));
            String contentText = content.asText();

            span.setAttribute(ATTR_LF_OUTPUT, contentText);
            span.setAttribute(ATTR_GENAI_PROMPT_TOKENS, promptTokens);
            span.setAttribute(ATTR_GENAI_INPUT_TOKENS, promptTokens);
            span.setAttribute(ATTR_GENAI_COMPLETION_TOKENS, completionTokens);
            span.setAttribute(ATTR_GENAI_OUTPUT_TOKENS, completionTokens);
            span.setAttribute(ATTR_GENAI_TOTAL_TOKENS, promptTokens + completionTokens);
            span.setAttribute(ATTR_GENAI_CACHE_READ_TOKENS, cacheReadTokens);
            return new LlmResponse(contentText, promptTokens, completionTokens, cacheReadTokens);
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
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
        Span span = tracer.spanBuilder("openai.embedding")
                .setAttribute(ATTR_LF_TYPE, "generation")
                .setAttribute(ATTR_GENAI_SYSTEM, "openai")
                .setAttribute(ATTR_GENAI_MODEL, aiProperties.getEmbeddingModel())
                .setAttribute(ATTR_LF_INPUT, text)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
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

            int promptTokens = response.path("usage").path("prompt_tokens").asInt(0);
            int totalTokens = response.path("usage").path("total_tokens").asInt(promptTokens);

            List<Double> vector = new ArrayList<>();
            for (JsonNode dimension : vectorNode) {
                vector.add(dimension.asDouble());
            }
            span.setAttribute(ATTR_LF_OUTPUT, "vector[" + vector.size() + "]");
            span.setAttribute(ATTR_GENAI_PROMPT_TOKENS, promptTokens);
            span.setAttribute(ATTR_GENAI_INPUT_TOKENS, promptTokens);
            span.setAttribute(ATTR_GENAI_TOTAL_TOKENS, totalTokens);
            return vector;
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
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
