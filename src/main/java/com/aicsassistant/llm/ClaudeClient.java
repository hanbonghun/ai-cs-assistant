package com.aicsassistant.llm;

import com.aicsassistant.common.config.AiProperties;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Claude(Anthropic Messages API) 채팅 클라이언트. {@code app.ai.provider=anthropic} 일 때만 뜬다.
 *
 * <p>{@link OpenAiClient} 는 임베딩 때문에 항상 떠 있으므로 {@link LlmClient} 가 두 개가 된다.
 * {@link OpenAiClient} 쪽에 {@code @Fallback} 을 달아 채팅 주입은 이쪽으로 오고, {@link EmbeddingClient}
 * 는 OpenAI 하나만 남는다 — Anthropic 에는 임베딩 API 가 없다. {@code @Primary} 를 쓰지 않는 이유는
 * 테스트가 페이크를 {@code @Primary} 로 덮어쓰기 때문이다 — 둘이 겹치면 주입이 모호해진다.
 *
 * <p>OpenAI 와 다른 점:
 * <ul>
 *   <li>system 은 messages 가 아니라 최상위 {@code system} 필드로 간다.</li>
 *   <li>Opus 5.5 는 {@code temperature} 를 받지 않고(400) 사고를 끌 수도 없다 — effort 로만 조절한다.</li>
 *   <li>캐시는 자동이 아니다. 최상위 {@code cache_control} 로 "마지막 메시지까지" 를 캐시 지점으로 잡는다.
 *       에이전트 루프는 매 스텝 앞부분이 그대로라 다음 스텝이 그 접두부를 읽는다.</li>
 *   <li>안전 분류기가 거절하면 HTTP 200 + {@code stop_reason=refusal} 로 온다. 운영은 서버 측 폴백을 켜고
 *       ({@code fallback}, 평가 judge 는 끈다), 그래도 거절이면 예외로 올린다 — 빈 본문을 파서에 넘기면 원인이
 *       "JSON 파싱 실패" 로 둔갑한다.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "anthropic")
public class ClaudeClient implements LlmClient {

    private static final AttributeKey<String> ATTR_LF_TYPE = AttributeKey.stringKey("langfuse.observation.type");
    private static final AttributeKey<String> ATTR_LF_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    private static final AttributeKey<String> ATTR_LF_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    private static final AttributeKey<String> ATTR_GENAI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> ATTR_GENAI_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Long> ATTR_GENAI_INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> ATTR_GENAI_OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<Long> ATTR_GENAI_CACHE_READ_TOKENS =
            AttributeKey.longKey("gen_ai.usage.cache_read_input_tokens");

    /** 비스트리밍 요청의 SDK 타임아웃 안에 드는 값. 사고 토큰도 여기에 포함된다. */
    private static final long MAX_TOKENS = 16_000L;
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final AiProperties.Anthropic properties;
    private final Tracer tracer;

    public ClaudeClient(AiProperties aiProperties, Tracer tracer) {
        this.properties = aiProperties.getAnthropic();
        this.tracer = tracer;
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(properties.getApiKey())
                .build();
    }

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
        MessageCreateParams params = buildRequest(messages);
        Span span = tracer.spanBuilder("anthropic.messages")
                .setAttribute(ATTR_LF_TYPE, "generation")
                .setAttribute(ATTR_GENAI_SYSTEM, "anthropic")
                .setAttribute(ATTR_GENAI_MODEL, properties.getModel())
                .setAttribute(ATTR_LF_INPUT, messages.toString())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Message response = client.messages().create(params);

            if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
                throw new IllegalStateException("Claude refused the request: " + response.stopDetails());
            }
            // 사고 블록은 버리고 텍스트 블록만 잇는다. Opus 5.5 는 사고가 항상 켜져 있어 content 첫 블록이
            // 텍스트라는 보장이 없다.
            String contentText = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining());
            if (contentText.isBlank()) {
                throw new IllegalStateException("Claude response missing text content (stop_reason="
                        + response.stopReason().map(Object::toString).orElse("?") + ")");
            }

            // OpenAI 의 prompt_tokens 는 캐시 적중분을 포함한 전체 입력이다. Anthropic 의 input_tokens 는
            // 캐시 밖 몫만이라, 비교가 되도록 캐시 읽기·쓰기를 더해 같은 의미로 맞춘다.
            long cacheRead = response.usage().cacheReadInputTokens().orElse(0L);
            long cacheWrite = response.usage().cacheCreationInputTokens().orElse(0L);
            int promptTokens = (int) (response.usage().inputTokens() + cacheRead + cacheWrite);
            int completionTokens = (int) response.usage().outputTokens();

            span.setAttribute(ATTR_LF_OUTPUT, contentText);
            span.setAttribute(ATTR_GENAI_INPUT_TOKENS, promptTokens);
            span.setAttribute(ATTR_GENAI_OUTPUT_TOKENS, completionTokens);
            span.setAttribute(ATTR_GENAI_CACHE_READ_TOKENS, cacheRead);
            return new LlmResponse(contentText, promptTokens, completionTokens, (int) cacheRead);
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * system 메시지는 최상위 {@code system} 으로 모으고 나머지는 순서대로 싣는다.
     *
     * <p>같은 역할이 연달아 와도(최초 문의 직후 고객 후속 답변 등) API 가 한 턴으로 합친다.
     */
    MessageCreateParams buildRequest(List<ChatMessage> messages) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(properties.getModel())
                .maxTokens(MAX_TOKENS)
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(properties.getEffort()))
                        .build())
                .cacheControl(CacheControlEphemeral.builder().build());
        if (properties.isFallback()) {
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        List<String> systemParts = new ArrayList<>();
        for (ChatMessage message : messages) {
            switch (message.role()) {
                case "system" -> systemParts.add(message.content());
                case "user" -> builder.addUserMessage(message.content());
                case "assistant" -> builder.addAssistantMessage(message.content());
                default -> throw new IllegalArgumentException("Unknown chat role: " + message.role());
            }
        }
        if (!systemParts.isEmpty()) {
            builder.system(String.join("\n\n", systemParts));
        }
        return builder.build();
    }

    @Override
    public String modelName() {
        return properties.getModel();
    }
}
