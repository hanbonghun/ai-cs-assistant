package com.aicsassistant.analysis.agent.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolCallBudgetInterceptorTest {

    private final ToolCallBudgetInterceptor interceptor = new ToolCallBudgetInterceptor();
    private final ObjectNode emptyInput = new ObjectMapper().createObjectNode();

    @Test
    void allowsCallsBelowBudget() {
        ToolCallContext ctx = new ToolCallContext(1L);
        for (int i = 0; i < ToolCallBudgetInterceptor.MAX_TOOL_CALLS_PER_RUN; i++) {
            assertThat(interceptor.beforeExecute("search_manual", emptyInput, ctx)).isEmpty();
            ctx.incrementToolCallCount();
        }
    }

    @Test
    void blocksCallAtBudgetLimit() {
        ToolCallContext ctx = new ToolCallContext(1L);
        for (int i = 0; i < ToolCallBudgetInterceptor.MAX_TOOL_CALLS_PER_RUN; i++) {
            ctx.incrementToolCallCount();
        }

        Optional<ToolResult> blocked = interceptor.beforeExecute("search_manual", emptyInput, ctx);

        assertThat(blocked).isPresent();
        ToolResult result = blocked.get();
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.PERMISSION);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.errorMessage()).contains("budget exhausted");
    }

    @Test
    void afterExecutePassesResultThrough() {
        ToolCallContext ctx = new ToolCallContext(1L);
        ToolResult original = ToolResult.success("ok");

        ToolResult result = interceptor.afterExecute("search_manual", emptyInput, original, ctx);

        assertThat(result).isSameAs(original);
    }
}
