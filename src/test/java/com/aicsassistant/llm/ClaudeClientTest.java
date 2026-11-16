package com.aicsassistant.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.common.config.AiProperties;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 요청 조립 테스트. OpenAI 와 메시지 형태가 달라 옮겨 실을 때 생기는 차이를 고정한다.
 */
class ClaudeClientTest {

    private ClaudeClient client;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        properties.getAnthropic().setApiKey("test-key");
        properties.getAnthropic().setModel("claude-opus-5-5");
        properties.getAnthropic().setEffort("medium");
        // 조립만 본다 — 전송·추적은 이 테스트의 대상이 아니다.
        client = new ClaudeClient(properties, null);
    }

    @Test
    void liftsSystemMessageToTopLevelField() {
        MessageCreateParams params = client.buildRequest(List.of(
                ChatMessage.system("You are an agent."),
                ChatMessage.user("환불해주세요"),
                ChatMessage.assistant("{\"action\":\"search_manual\"}"),
                ChatMessage.user("Observation:\n...")));

        assertThat(params.system().flatMap(MessageCreateParams.System::string)).contains("You are an agent.");
        assertThat(params.messages()).extracting(MessageParam::role).containsExactly(
                MessageParam.Role.USER, MessageParam.Role.ASSISTANT, MessageParam.Role.USER);
        assertThat(params.model().asString()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void enablesServerSideRefusalFallback() {
        MessageCreateParams params = client.buildRequest(List.of(ChatMessage.user("hi")));

        assertThat(params._additionalBodyProperties()).containsEntry("fallbacks", JsonValue.from("default"));
        assertThat(params._headers().values("anthropic-beta")).contains("server-side-fallback-2026-07-01");
    }

    @Test
    void omitsFallbackWhenDisabled() {
        AiProperties properties = new AiProperties();
        properties.getAnthropic().setApiKey("test-key");
        properties.getAnthropic().setModel("claude-opus-5-5");
        properties.getAnthropic().setEffort("medium");
        properties.getAnthropic().setFallback(false);

        MessageCreateParams params = new ClaudeClient(properties, null).buildRequest(List.of(ChatMessage.user("hi")));

        assertThat(params._additionalBodyProperties()).doesNotContainKey("fallbacks");
        assertThat(params._headers().values("anthropic-beta")).isEmpty();
    }

    @Test
    void rejectsUnknownRole() {
        assertThatThrownBy(() -> client.buildRequest(List.of(new ChatMessage("tool", "x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
