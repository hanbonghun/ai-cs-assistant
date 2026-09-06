package com.aicsassistant.analysis.infra.llm;

import com.aicsassistant.common.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
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
        ObjectNode requestBody = buildChatRequest(messages);
        Span span = tracer.spanBuilder("openai.chat.completion")
                .setAttribute(ATTR_LF_TYPE, "generation")
                .setAttribute(ATTR_GENAI_SYSTEM, "openai")
                .setAttribute(ATTR_GENAI_MODEL, aiProperties.getModel())
                .setAttribute(ATTR_LF_INPUT, requestBody.path("messages").toString())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            JsonNode response = webClient.post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + aiProperties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
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

    /**
     * 채팅 요청 본문을 구조적으로 만든다.
     *
     * <p>문자열 템플릿으로 조립하지 않는 이유: 본문에 실리는 값의 대부분이 고객이 쓴 텍스트다.
     * 인용부호 하나가 어긋나면 요청 전체가 무효해지고, 그 실패는 OpenAI 400 으로만 보여
     * 원인이 어느 필드인지 알 수 없다. 이스케이프는 Jackson 이 한다.
     */
    ObjectNode buildChatRequest(List<ChatMessage> messages) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", aiProperties.getModel());
        ArrayNode messageArray = body.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode node = messageArray.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
        }
        body.put("temperature", 0.1);
        return body;
    }

    ObjectNode buildEmbeddingRequest(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", aiProperties.getEmbeddingModel());
        body.put("input", text);
        return body;
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
                    .bodyValue(buildEmbeddingRequest(text))
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
}
