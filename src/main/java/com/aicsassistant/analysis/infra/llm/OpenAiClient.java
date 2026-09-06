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
import java.util.Collections;
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

    /**
     * 한 요청에 실을 최대 입력 수. text-embedding-3-small 은 입력 2048개까지 받지만 요청당 토큰
     * 상한이 따로 있어, 500자 청크(≈150토큰) 기준으로 여유가 남는 값을 쓴다.
     */
    private static final int EMBEDDING_BATCH_SIZE = 100;

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

    /** 묶음 요청. {@code input} 에 배열을 실으면 한 번의 왕복으로 여러 벡터를 받는다. */
    ObjectNode buildEmbeddingRequest(List<String> texts) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", aiProperties.getEmbeddingModel());
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
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

            List<Double> vector = toVector(response.path("data").path(0).path("embedding"));

            int promptTokens = response.path("usage").path("prompt_tokens").asInt(0);
            int totalTokens = response.path("usage").path("total_tokens").asInt(promptTokens);

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
    public List<List<Double>> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<List<Double>> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += EMBEDDING_BATCH_SIZE) {
            int to = Math.min(texts.size(), from + EMBEDDING_BATCH_SIZE);
            vectors.addAll(embedBatch(texts.subList(from, to)));
        }
        return vectors;
    }

    /**
     * 묶음 한 덩어리를 임베딩한다.
     *
     * <p><b>응답 {@code data[]} 의 순서는 보장되지 않는다.</b> 각 항목의 {@code index} 로 제자리에
     * 놓는다 — 순서를 믿고 그대로 담으면 청크와 벡터가 어긋나고, 그 오류는 예외가 아니라
     * "검색이 엉뚱한 문서를 집는다" 로만 드러나 알아채기 어렵다.
     *
     * <p>개수가 어긋나도 같은 이유로 실패시킨다. 모자란 채로 진행하면 어느 청크의 벡터가
     * 빠졌는지 알 수 없다.
     */
    private List<List<Double>> embedBatch(List<String> batch) {
        Span span = tracer.spanBuilder("openai.embedding.batch")
                .setAttribute(ATTR_LF_TYPE, "generation")
                .setAttribute(ATTR_GENAI_SYSTEM, "openai")
                .setAttribute(ATTR_GENAI_MODEL, aiProperties.getEmbeddingModel())
                .setAttribute(ATTR_LF_INPUT, batch.size() + " inputs")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            JsonNode response = webClient.post()
                    .uri("https://api.openai.com/v1/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + aiProperties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(buildEmbeddingRequest(batch))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null) {
                throw new IllegalStateException("OpenAI embedding returned empty response");
            }
            JsonNode data = response.path("data");
            if (!data.isArray() || data.size() != batch.size()) {
                throw new IllegalStateException("OpenAI embedding returned " + data.size()
                        + " vectors for " + batch.size() + " inputs");
            }

            List<List<Double>> vectors = new ArrayList<>(Collections.nCopies(batch.size(), null));
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= batch.size()) {
                    throw new IllegalStateException("OpenAI embedding returned out-of-range index: " + index);
                }
                vectors.set(index, toVector(item.path("embedding")));
            }

            int promptTokens = response.path("usage").path("prompt_tokens").asInt(0);
            int totalTokens = response.path("usage").path("total_tokens").asInt(promptTokens);
            span.setAttribute(ATTR_LF_OUTPUT, "vector[" + batch.size() + " x " + vectors.get(0).size() + "]");
            span.setAttribute(ATTR_GENAI_PROMPT_TOKENS, promptTokens);
            span.setAttribute(ATTR_GENAI_INPUT_TOKENS, promptTokens);
            span.setAttribute(ATTR_GENAI_TOTAL_TOKENS, totalTokens);
            return vectors;
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private List<Double> toVector(JsonNode embeddingNode) {
        if (!embeddingNode.isArray()) {
            throw new IllegalStateException("OpenAI embedding missing vector");
        }
        List<Double> vector = new ArrayList<>(embeddingNode.size());
        for (JsonNode dimension : embeddingNode) {
            vector.add(dimension.asDouble());
        }
        return vector;
    }

    @Override
    public String modelName() {
        return aiProperties.getModel();
    }
}
