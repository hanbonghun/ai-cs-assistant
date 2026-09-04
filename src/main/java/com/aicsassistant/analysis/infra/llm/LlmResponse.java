package com.aicsassistant.analysis.infra.llm;

/**
 * LLM 호출 결과 — 응답 텍스트와 토큰 사용량을 함께 담는다.
 *
 * <p>{@code cacheReadTokens} 는 {@code promptTokens} 중 프롬프트 캐시에서 재사용된 몫이다.
 * 에이전트 루프는 매 스텝마다 전체 메시지를 재전송하므로 앞부분이 반복해서 실려 나간다 —
 * 8스텝이면 시스템 프롬프트가 8번 전송된다. 캐시가 걸리면 그 반복분이 할인 단가로 계산되고
 * 응답도 빨라진다. 캐시가 깨지는 사고는 에러 없이 비용만 오르므로 관측 가능해야 한다.
 *
 * <p>OpenAI 는 gpt-5.6 이전 모델에서 최소 2,048 입력 토큰 이상의 접두부만 캐시한다.
 * 우리 시스템 프롬프트는 그 경계 부근이라, 초기 스텝은 0 이고 대화가 길어지는 후반 스텝부터
 * 값이 붙는 패턴이 정상이다. 전 스텝이 0 이면 접두부가 요청마다 달라지고 있다는 신호다.
 */
public record LlmResponse(
        String content,
        int promptTokens,
        int completionTokens,
        int cacheReadTokens
) {
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
