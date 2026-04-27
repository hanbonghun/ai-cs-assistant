package com.aicsassistant.analysis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * 도구 실행 전후에 끼어들어 호출을 차단하거나 결과를 변형하는 가드 훅.
 *
 * <p>LLM이 시스템 프롬프트의 정책을 어겨도 코드 레벨에서 강제할 수 있도록 한다.
 */
public interface ToolCallInterceptor {

    /**
     * 도구 실행 직전에 호출된다. {@code Optional.of(...)}를 반환하면 실제 도구는 실행되지 않고
     * 반환된 {@link ToolResult}가 LLM에 전달된다.
     */
    default Optional<ToolResult> beforeExecute(String toolName, JsonNode input, ToolCallContext ctx) {
        return Optional.empty();
    }

    /**
     * 도구 실행 직후에 호출된다. 반환된 결과가 LLM에 전달되므로, 가드 노트를 덧붙이거나
     * 성공 결과를 거부 결과로 바꿀 수 있다.
     */
    default ToolResult afterExecute(String toolName, JsonNode input, ToolResult result, ToolCallContext ctx) {
        return result;
    }
}
