package com.aicsassistant.analysis.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 모델이 고른 도구를 실제로 실행하는 경로. 인터셉터 체인 · 타입 변환 · 실패의 관측 가능한 표현이
 * 여기 모인다.
 *
 * <p>루프에서 분리한 이유: 루프는 "모델이 다음에 무엇을 하기로 했나" 를 읽는 곳이고, 여기는
 * "그 결정을 어떤 정책 아래 실행하나" 를 다룬다. 정책(ADR 0003)이 두 겹으로 붙는 지점이라
 * 루프와 섞어두면 어느 쪽을 고치는지 헷갈린다.
 *
 * <p>이 클래스는 <b>예외를 밖으로 던지지 않는다</b>(도구 이름을 못 찾는 경우만 예외다 — 그건 모델이
 * 프로토콜을 어긴 것이라 파싱 실패와 같은 취급을 받아야 한다). 실행 실패는 {@link ToolResult#error}
 * 로 돌려주고, 루프가 그것을 Observation 으로 모델에게 전달한다. 모델이 에러 유형에 맞는 다음
 * 행동(재시도 / 입력 수정 / 상담사 라우팅)을 스스로 고를 수 있어야 하기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ToolInvoker {

    private final List<ToolCallInterceptor> interceptors;
    private final ObjectMapper objectMapper;

    /**
     * 인터셉터 체인을 통과시켜 도구를 실행한다.
     *
     * <p>{@code beforeExecute} 가 차단하면 {@code afterExecute} 를 아예 돌리지 않는다 —
     * 차단된 호출은 부수효과를 남기지 않아야 한다. 호출 횟수도 실제로 실행된 것만 센다.
     */
    ToolResult invoke(List<AgentTool<?>> tools, String action, JsonNode input,
                      ToolCallContext ctx, int step) {

        for (ToolCallInterceptor interceptor : interceptors) {
            Optional<ToolResult> blocked = interceptor.beforeExecute(action, input, ctx);
            if (blocked.isPresent()) {
                log.info("[Agent inquiryId={} step={}] action={} blocked_by={}",
                        ctx.inquiryId(), step, action, interceptor.getClass().getSimpleName());
                return blocked.get();
            }
        }

        ToolResult result = executeTyped(resolveTool(tools, action), input, action, ctx, step);
        ctx.incrementToolCallCount();

        for (ToolCallInterceptor interceptor : interceptors) {
            result = interceptor.afterExecute(action, input, result, ctx);
        }
        return result;
    }

    /** 도구 결과를 모델에게 보여줄 Observation JSON 으로 만든다. */
    String serializeObservation(ToolResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"ok\":false,\"errorCategory\":\"TRANSIENT\",\"isRetryable\":true,"
                    + "\"errorMessage\":\"Failed to serialize tool result\"}";
        }
    }

    /**
     * 도구 입력을 선언된 record 타입으로 변환해 실행한다.
     *
     * <p>변환 실패는 {@code VALIDATION} — 모델이 스키마를 잘못 읽은 것이므로 입력을 고쳐 다시
     * 시도해야 한다. 실행 중 예외는 {@code TRANSIENT} — 우리 쪽 문제라 같은 호출을 한 번 더
     * 해볼 만하다. 이 구분이 모델의 다음 행동을 가른다.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ToolResult executeTyped(AgentTool<?> tool, JsonNode input, String action,
                                    ToolCallContext ctx, int step) {
        Object typedInput;
        try {
            typedInput = objectMapper.treeToValue(input, tool.inputType());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.info("[Agent inquiryId={} step={}] action={} input parse failed: {}",
                    ctx.inquiryId(), step, action, e.getMessage());
            return ToolResult.error(
                    ToolErrorCategory.VALIDATION,
                    false,
                    "Tool input does not match the declared schema: " + e.getMessage());
        }
        try {
            return ((AgentTool) tool).execute(typedInput);
        } catch (Exception e) {
            log.warn("[Agent inquiryId={} step={}] tool error action={}", ctx.inquiryId(), step, action, e);
            return ToolResult.error(
                    ToolErrorCategory.TRANSIENT,
                    true,
                    "Tool execution failed: " + e.getMessage());
        }
    }

    private AgentTool<?> resolveTool(List<AgentTool<?>> tools, String name) {
        return tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown tool requested by agent: " + name));
    }
}
