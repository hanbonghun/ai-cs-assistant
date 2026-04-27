package com.aicsassistant.analysis.agent.interceptor;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolCallInterceptor;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 한 분석 세션에서 허용되는 도구 호출 횟수를 강제한다.
 *
 * <p>프롬프트에도 "do not exceed 6 tool calls" 지시가 있지만, LLM이 따르지 않을 수 있으므로
 * 코드 레벨에서 PERMISSION 에러로 차단해 finalAnswer 생성을 강제한다.
 */
@Component
public class ToolCallBudgetInterceptor implements ToolCallInterceptor {

    static final int MAX_TOOL_CALLS_PER_RUN = 6;

    @Override
    public Optional<ToolResult> beforeExecute(String toolName, JsonNode input, ToolCallContext ctx) {
        if (ctx.toolCallCount() >= MAX_TOOL_CALLS_PER_RUN) {
            return Optional.of(ToolResult.error(
                    ToolErrorCategory.PERMISSION,
                    false,
                    "Tool call budget exhausted (max " + MAX_TOOL_CALLS_PER_RUN
                            + " tool calls per inquiry analysis). Stop calling tools and produce finalAnswer "
                            + "with current information. Set needsHumanReview: true if information is insufficient."));
        }
        return Optional.empty();
    }
}
