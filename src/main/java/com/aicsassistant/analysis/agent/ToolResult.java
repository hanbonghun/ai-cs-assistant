package com.aicsassistant.analysis.agent;

/**
 * 도구 실행 결과. 성공 시 {@code data}만, 실패 시 {@code errorCategory}/{@code isRetryable}/{@code errorMessage}만 채워진다.
 *
 * <p>LLM에게 전달될 때는 JSON으로 직렬화되어 에이전트가 에러 유형에 맞는 다음 행동을 선택할 수 있다.
 */
public record ToolResult(
        boolean ok,
        String data,
        ToolErrorCategory errorCategory,
        boolean isRetryable,
        String errorMessage
) {

    public static ToolResult success(String data) {
        return new ToolResult(true, data, null, false, null);
    }

    public static ToolResult error(ToolErrorCategory category, boolean retryable, String message) {
        return new ToolResult(false, null, category, retryable, message);
    }
}
