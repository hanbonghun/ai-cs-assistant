package com.aicsassistant.analysis.agent;

import com.aicsassistant.inquiry.domain.Inquiry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;

/**
 * 에이전트 실행 한 건의 관측 트레이스.
 *
 * <p>루프에서 분리한 이유: 실행이 끝나는 지점이 셋(최종 답변·추가 질문·강제 종료)이라 같은 attribute
 * 조립이 세 번 반복됐고, 그 사이에서 정작 루프의 판단이 묻혔다. "무엇을 관측하는가" 를 여기 모으면
 * 관측 항목을 추가할 때 루프를 건드리지 않아도 된다.
 *
 * <p>attribute 를 두 계열로 다는 이유: {@code langfuse.*} 는 Langfuse 가 trace 이름·입출력·세션·
 * 태그로 해석하고, {@code agent.*} 는 우리가 세는 값이다. LLM 호출의 {@code gen_ai.*} 는 한 단계
 * 아래인 {@code OpenAiClient} 가 단다.
 *
 * <p>{@link AutoCloseable} 이라 try-with-resources 로 쓴다 — 닫으면 scope 를 벗고 span 을 끝낸다.
 */
final class AgentTrace implements AutoCloseable {

    static final String OUTCOME_FINAL_ANSWER = "final_answer";
    static final String OUTCOME_FOLLOW_UP = "follow_up";
    static final String OUTCOME_FORCED_FINAL = "forced_final_answer";
    static final String OUTCOME_FORCED_SYNTHETIC = "forced_final_synthetic";

    private static final AttributeKey<String> ATTR_LF_TRACE_NAME = AttributeKey.stringKey("langfuse.trace.name");
    private static final AttributeKey<String> ATTR_LF_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    private static final AttributeKey<String> ATTR_LF_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    private static final AttributeKey<String> ATTR_LF_SESSION_ID = AttributeKey.stringKey("langfuse.session.id");
    private static final AttributeKey<String> ATTR_LF_USER_ID = AttributeKey.stringKey("langfuse.user.id");
    private static final AttributeKey<List<String>> ATTR_LF_TAGS = AttributeKey.stringArrayKey("langfuse.trace.tags");
    private static final AttributeKey<Long> ATTR_INQUIRY_ID = AttributeKey.longKey("inquiry.id");
    private static final AttributeKey<Long> ATTR_TOTAL_TOKENS = AttributeKey.longKey("agent.total_tokens");
    private static final AttributeKey<Long> ATTR_STEP_COUNT = AttributeKey.longKey("agent.steps");
    private static final AttributeKey<String> ATTR_AGENT_OUTCOME = AttributeKey.stringKey("agent.outcome");
    private static final AttributeKey<String> ATTR_TOOL_NAME = AttributeKey.stringKey("agent.tool");
    private static final AttributeKey<Long> ATTR_STEP_INDEX = AttributeKey.longKey("agent.step.index");

    private final Tracer tracer;
    private final Span span;
    private final Scope scope;

    private AgentTrace(Tracer tracer, Span span) {
        this.tracer = tracer;
        this.span = span;
        this.scope = span.makeCurrent();
    }

    static AgentTrace start(Tracer tracer, Inquiry inquiry) {
        Span span = tracer.spanBuilder("inquiry-analysis-agent")
                .setAttribute(ATTR_LF_TRACE_NAME, "inquiry-analysis-agent")
                .setAttribute(ATTR_INQUIRY_ID, inquiry.getId())
                .setAttribute(ATTR_LF_INPUT, inquiry.getContent())
                .setAttribute(ATTR_LF_SESSION_ID, "inquiry-" + inquiry.getId())
                .setAttribute(ATTR_LF_USER_ID, safeUserId(inquiry))
                .startSpan();
        return new AgentTrace(tracer, span);
    }

    /** 스텝 하나를 감싸는 자식 span. try-with-resources 로 쓴다. */
    StepSpan step(int index) {
        return new StepSpan(tracer.spanBuilder("agent-step")
                .setAttribute(ATTR_STEP_INDEX, (long) index)
                .startSpan());
    }

    /**
     * 최종 답변으로 끝난 실행을 기록한다.
     *
     * <p>태그는 반드시 분류 결과가 나온 뒤에 달아야 한다. 이전에는 span 을 만들 때 {@code Inquiry}
     * 에서 읽었는데, 그 시점은 분석 전이라 category/urgency 가 둘 다 null 이었다 — 태그가 항상
     * 빈 배열로 들어가 Langfuse 에 {@code [{"arrayValue":{}}]} 로 보였고 필터가 아예 안 먹었다.
     *
     * @param outcome {@link #OUTCOME_FINAL_ANSWER} · {@link #OUTCOME_FORCED_FINAL} ·
     *                {@link #OUTCOME_FORCED_SYNTHETIC} 중 하나. 뒤 둘의 비율이 오르면 모델이
     *                형식을 못 맞추고 있다는 신호다
     */
    void recordAnswer(AgentResult.FinalAnswer answer, String outcome, int totalTokens, int stepCount) {
        span.setAttribute(ATTR_AGENT_OUTCOME, outcome);
        span.setAttribute(ATTR_LF_TAGS, buildTags(answer.category(), answer.urgency()));
        span.setAttribute(ATTR_LF_OUTPUT, answer.answer());
        span.setAttribute(ATTR_TOTAL_TOKENS, totalTokens);
        span.setAttribute(ATTR_STEP_COUNT, (long) stepCount);
    }

    /** 추가 질문으로 끝난 실행. 아직 분류가 없으므로 태그를 달지 않는다. */
    void recordFollowUp(String question, int totalTokens, int stepCount) {
        span.setAttribute(ATTR_AGENT_OUTCOME, OUTCOME_FOLLOW_UP);
        span.setAttribute(ATTR_LF_OUTPUT, question);
        span.setAttribute(ATTR_TOTAL_TOKENS, totalTokens);
        span.setAttribute(ATTR_STEP_COUNT, (long) stepCount);
    }

    void recordError(RuntimeException e) {
        span.setStatus(StatusCode.ERROR, e.getMessage());
        span.recordException(e);
    }

    @Override
    public void close() {
        scope.close();
        span.end();
    }

    /** Langfuse 트레이스 태그. 분류 결과가 나온 뒤에 호출해야 한다. */
    static List<String> buildTags(String category, String urgency) {
        List<String> tags = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            tags.add("category:" + category);
        }
        if (urgency != null && !urgency.isBlank()) {
            tags.add("urgency:" + urgency);
        }
        return tags;
    }

    static String safeUserId(Inquiry inquiry) {
        String id = inquiry.getCustomerIdentifier();
        return id == null || id.isBlank() ? "anonymous" : id;
    }

    /** 스텝 span. 닫으면 scope 를 벗고 span 을 끝낸다. */
    static final class StepSpan implements AutoCloseable {

        private final Span span;
        private final Scope scope;

        private StepSpan(Span span) {
            this.span = span;
            this.scope = span.makeCurrent();
        }

        /** 이 스텝이 고른 도구. 모델이 툴을 어떻게 고르는지 세는 데 쓴다. */
        void tool(String name) {
            span.setAttribute(ATTR_TOOL_NAME, name);
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
