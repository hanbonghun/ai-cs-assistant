package com.aicsassistant.analysis.infra.llm;

/**
 * LLM 호출 결과 — 응답 텍스트와 토큰 사용량을 함께 담는다.
 */
public record LlmResponse(
        String content,
        int promptTokens,
        int completionTokens
) {
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
