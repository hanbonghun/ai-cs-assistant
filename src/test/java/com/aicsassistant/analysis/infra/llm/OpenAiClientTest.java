package com.aicsassistant.analysis.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.common.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 요청 본문 조립 테스트.
 *
 * <p>이전에는 Jackson 이 만든 messages JSON 을 다시 텍스트 템플릿의 {@code %s} 에 꽂았다.
 * 값 하나가 인용부호를 깨면 요청 전체가 무효해지는데, 그 실패는 OpenAI 400 으로만 보인다.
 */
class OpenAiClientTest {

    private static final String MODEL = "gpt-4.1-mini";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenAiClient client;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        properties.setModel(MODEL);
        properties.setEmbeddingModel("text-embedding-3-small");
        properties.setApiKey("test-key");
        // 본문 조립은 전송·추적을 거치지 않는다 — 그 둘은 이 테스트의 대상이 아니다.
        client = new OpenAiClient(null, properties, objectMapper, null);
    }

    @Test
    void buildsChatRequestWithModelAndMessages() {
        JsonNode body = client.buildChatRequest(List.of(
                ChatMessage.system("You are an agent."),
                ChatMessage.user("환불해주세요")));

        assertThat(body.path("model").asText()).isEqualTo(MODEL);
        assertThat(body.path("messages")).hasSize(2);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").path(1).path("content").asText()).isEqualTo("환불해주세요");
    }

    /** 고객이 쓴 텍스트가 그대로 실려 나가므로, 인용부호·줄바꿈이 본문을 깨뜨리면 안 된다. */
    @Test
    void escapesQuotesAndNewlinesInMessageContent() {
        String hostile = "그가 \"환불\" 이라 했다\n두 번째 줄\\백슬래시";

        JsonNode body = client.buildChatRequest(List.of(ChatMessage.user(hostile)));

        assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo(hostile);
    }

    @Test
    void buildsEmbeddingRequestWithoutHandRolledEscaping() {
        String hostile = "정책 \"환불\"\n조항";

        JsonNode body = client.buildEmbeddingRequest(hostile);

        assertThat(body.path("model").asText()).isEqualTo("text-embedding-3-small");
        assertThat(body.path("input").asText()).isEqualTo(hostile);
    }
}
