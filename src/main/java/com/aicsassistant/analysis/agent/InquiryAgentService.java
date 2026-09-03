package com.aicsassistant.analysis.agent;

import com.aicsassistant.analysis.agent.tool.CheckOrderStatusTool;
import com.aicsassistant.analysis.agent.tool.SearchFaqTool;
import com.aicsassistant.analysis.agent.tool.SearchManualTool;
import com.aicsassistant.faq.InMemoryFaqRepository;
import com.aicsassistant.analysis.application.ManualRetrievalService;
import com.aicsassistant.analysis.application.PromptFactory;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.infra.llm.ChatMessage;
import com.aicsassistant.analysis.infra.llm.LlmClient;
import com.aicsassistant.analysis.infra.llm.LlmResponse;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ReAct (Reasoning + Acting) 에이전트 루프.
 *
 * <p>매 스텝마다 LLM은 세 가지 중 하나를 선택한다:
 * <ol>
 *   <li>툴 호출 — 필요한 정보를 수집</li>
 *   <li>followUpQuestion — 고객에게 추가 정보 요청</li>
 *   <li>finalAnswer — 최종 답변 생성</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryAgentService {

    private static final int MAX_STEPS = 8;

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

    private final LlmClient llmClient;
    private final ManualRetrievalService manualRetrievalService;
    private final PromptFactory promptFactory;
    private final ObjectMapper objectMapper;
    private final InMemoryOrderRepository orderRepository;
    private final InMemoryFaqRepository faqRepository;
    private final List<ToolCallInterceptor> interceptors;
    private final Tracer tracer;

    /**
     * @param inquiry         분석할 문의
     * @param conversationHistory 이전 대화 메시지 (최초 분석 시 빈 리스트)
     */
    public AgentResult run(Inquiry inquiry, List<InquiryMessage> conversationHistory) {
        CheckOrderStatusTool orderTool = new CheckOrderStatusTool(orderRepository, inquiry.getCustomerIdentifier());
        SearchManualTool searchTool = new SearchManualTool(manualRetrievalService);
        SearchFaqTool faqTool = new SearchFaqTool(faqRepository);
        List<AgentTool<?>> tools = List.of(faqTool, searchTool, orderTool);

        Span agentSpan = tracer.spanBuilder("inquiry-analysis-agent")
                .setAttribute(ATTR_LF_TRACE_NAME, "inquiry-analysis-agent")
                .setAttribute(ATTR_INQUIRY_ID, inquiry.getId())
                .setAttribute(ATTR_LF_INPUT, inquiry.getContent())
                .setAttribute(ATTR_LF_SESSION_ID, "inquiry-" + inquiry.getId())
                .setAttribute(ATTR_LF_USER_ID, safeUserId(inquiry))
                .setAttribute(ATTR_LF_TAGS, buildTags(inquiry))
                .startSpan();
        try (Scope ignored = agentSpan.makeCurrent()) {
            return runAgentLoop(inquiry, conversationHistory, tools, orderTool, searchTool, agentSpan);
        } catch (RuntimeException e) {
            agentSpan.setStatus(StatusCode.ERROR, e.getMessage());
            agentSpan.recordException(e);
            throw e;
        } finally {
            agentSpan.end();
        }
    }

    private AgentResult runAgentLoop(
            Inquiry inquiry,
            List<InquiryMessage> conversationHistory,
            List<AgentTool<?>> tools,
            CheckOrderStatusTool orderTool,
            SearchManualTool searchTool,
            Span agentSpan) {

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(promptFactory.buildAgentSystemPrompt(tools)));

        // 최초 문의 내용 (주문번호가 있으면 주문 정보 선주입)
        messages.add(ChatMessage.user(buildInitialMessage(inquiry, orderTool)));

        // 이전 대화 히스토리 주입 (CUSTOMER → user, AI → assistant)
        for (InquiryMessage msg : conversationHistory) {
            if (msg.getRole() == InquiryMessageRole.AI) {
                messages.add(ChatMessage.assistant(msg.getContent()));
            } else {
                messages.add(ChatMessage.user(promptFactory.fenceCustomerText(msg.getContent())));
            }
        }

        List<AgentStep> steps = new ArrayList<>();
        int totalTokens = 0;
        ToolCallContext callContext = new ToolCallContext(inquiry.getId(), inquiry.getCustomerIdentifier());

        for (int step = 0; step < MAX_STEPS; step++) {
            Span stepSpan = tracer.spanBuilder("agent-step")
                    .setAttribute(AttributeKey.longKey("agent.step.index"), (long) step)
                    .startSpan();
            try (Scope ignored = stepSpan.makeCurrent()) {
                LlmResponse llmResponse = llmClient.completeWithUsage(messages);
                totalTokens += llmResponse.totalTokens();
                String raw = llmResponse.content();
                log.debug("[Agent inquiryId={} step={} tokens={}] raw={}", inquiry.getId(), step, llmResponse.totalTokens(), raw);

                JsonNode node = parseJson(raw);
                String thought = node.path("thought").asText("");

                if (node.has("finalAnswer")) {
                    log.info("[Agent done] inquiryId={} steps={} totalTokens={}", inquiry.getId(), step, totalTokens);
                    AgentResult.FinalAnswer result = buildFinalAnswer(node, steps, searchTool.getCollectedChunks(), totalTokens);
                    agentSpan.setAttribute(ATTR_AGENT_OUTCOME, "final_answer");
                    agentSpan.setAttribute(ATTR_LF_OUTPUT, result.answer());
                    agentSpan.setAttribute(ATTR_TOTAL_TOKENS, totalTokens);
                    agentSpan.setAttribute(ATTR_STEP_COUNT, (long) step + 1);
                    return result;
                }

                if (node.has("followUpQuestion")) {
                    String question = node.path("followUpQuestion").asText("").strip();
                    log.info("[Agent followUp] inquiryId={} steps={} totalTokens={}", inquiry.getId(), step, totalTokens);
                    agentSpan.setAttribute(ATTR_AGENT_OUTCOME, "follow_up");
                    agentSpan.setAttribute(ATTR_LF_OUTPUT, question);
                    agentSpan.setAttribute(ATTR_TOTAL_TOKENS, totalTokens);
                    agentSpan.setAttribute(ATTR_STEP_COUNT, (long) step + 1);
                    return new AgentResult.FollowUpQuestion(question, List.copyOf(steps), totalTokens);
                }

                String action = node.path("action").asText("");
                JsonNode actionInput = node.path("actionInput");
                stepSpan.setAttribute(ATTR_TOOL_NAME, action);

                AgentTool<?> tool = resolveTool(tools, action);
                ToolResult toolResult = invokeWithInterceptors(tool, action, actionInput, callContext, inquiry.getId(), step);

                String observation = serializeObservation(toolResult);
                log.info("[Agent inquiryId={} step={}] action={} ok={} category={} observation_len={}",
                        inquiry.getId(), step, action, toolResult.ok(), toolResult.errorCategory(), observation.length());

                // search_manual 스텝에는 이번 호출에서 가져온 문서 목록을 첨부
                List<RetrievedManualChunkDto> stepChunks = (tool instanceof SearchManualTool s) ? s.getLastCallChunks() : List.of();
                steps.add(new AgentStep(thought, action, actionInput.toString(), observation, stepChunks));
                messages.add(ChatMessage.assistant(raw));
                messages.add(ChatMessage.user("Observation:\n" + observation));
            } finally {
                stepSpan.end();
            }
        }

        // 스텝 소진 — 여기까지 온 문의가 가장 복잡한 건이므로 실패시키지 않고 답을 뽑아낸다
        return forceFinalAnswerWithoutTools(inquiry, messages, steps, searchTool, totalTokens, agentSpan);
    }

    /**
     * 툴을 뺀 마지막 한 라운드를 돌려 finalAnswer를 강제한다.
     *
     * <p>스텝을 소진한 문의는 가장 복잡해서 사람이 봐야 하는 건이다. 예외로 끝내면 답변도 상담사
     * 브리핑도 남지 않으므로, 툴이 없다고 알린 뒤 지금까지 모은 정보로 요약을 받는다.
     * 그마저 형식을 못 맞추면 코드로 브리핑을 합성한다 — 어느 경로든 문의가 유실되지 않는다.
     */
    private AgentResult.FinalAnswer forceFinalAnswerWithoutTools(
            Inquiry inquiry,
            List<ChatMessage> messages,
            List<AgentStep> steps,
            SearchManualTool searchTool,
            int totalTokens,
            Span agentSpan) {

        messages.add(ChatMessage.user(
                "Step budget for this inquiry is exhausted — no further tool calls are possible and any "
                + "\"action\" you return now will be discarded.\n"
                + "Respond with the finalAnswer form ONLY. Summarize what you gathered as a briefing for "
                + "the counselor and set needsHumanReview: true."));

        int tokens = totalTokens;
        AgentResult.FinalAnswer answer = null;
        try {
            LlmResponse response = llmClient.completeWithUsage(messages);
            tokens += response.totalTokens();
            JsonNode node = parseJson(response.content());
            if (node.has("finalAnswer")) {
                answer = buildFinalAnswer(node, steps, searchTool.getCollectedChunks(), tokens)
                        .withHumanReview();
            }
        } catch (RuntimeException e) {
            log.warn("[Agent] 강제 finalAnswer 라운드 실패 inquiryId={}", inquiry.getId(), e);
        }

        boolean synthetic = answer == null;
        if (synthetic) {
            answer = syntheticBriefing(inquiry, steps, searchTool.getCollectedChunks(), tokens);
        }

        log.info("[Agent forced final] inquiryId={} steps={} totalTokens={} synthetic={}",
                inquiry.getId(), steps.size(), tokens, synthetic);
        agentSpan.setAttribute(ATTR_AGENT_OUTCOME, synthetic ? "forced_final_synthetic" : "forced_final_answer");
        agentSpan.setAttribute(ATTR_LF_OUTPUT, answer.answer());
        agentSpan.setAttribute(ATTR_TOTAL_TOKENS, tokens);
        agentSpan.setAttribute(ATTR_STEP_COUNT, (long) steps.size());
        return answer;
    }

    /** LLM이 마지막 라운드에서도 형식을 못 맞춘 경우의 결정론적 대체 결과. */
    private AgentResult.FinalAnswer syntheticBriefing(
            Inquiry inquiry, List<AgentStep> steps, List<RetrievedManualChunkDto> chunks, int totalTokens) {

        String toolsUsed = steps.stream()
                .map(AgentStep::action)
                .distinct()
                .collect(Collectors.joining(", "));
        String briefing = "AI가 %d스텝 안에 분석을 마치지 못했습니다. 호출한 도구: %s. 상세 내역은 분석 로그를 확인해 주세요."
                .formatted(MAX_STEPS, toolsUsed.isBlank() ? "없음" : toolsUsed);

        return new AgentResult.FinalAnswer(
                briefing,
                inquiry.getCategory() != null ? inquiry.getCategory().name() : InquiryCategory.GENERAL.name(),
                inquiry.getUrgency() != null ? inquiry.getUrgency().name() : UrgencyLevel.MEDIUM.name(),
                true,
                false,
                false,
                "[자동 합성] 최대 스텝(" + MAX_STEPS + ") 소진 후에도 LLM이 finalAnswer 형식을 반환하지 않아 상담사에게 라우팅합니다.",
                List.copyOf(steps),
                chunks,
                totalTokens);
    }

    static String safeUserId(Inquiry inquiry) {
        String id = inquiry.getCustomerIdentifier();
        return id == null || id.isBlank() ? "anonymous" : id;
    }

    static List<String> buildTags(Inquiry inquiry) {
        List<String> tags = new ArrayList<>();
        if (inquiry.getCategory() != null) {
            tags.add("category:" + inquiry.getCategory().name());
        }
        if (inquiry.getUrgency() != null) {
            tags.add("urgency:" + inquiry.getUrgency().name());
        }
        return tags;
    }

    /**
     * 최초 사용자 메시지를 조립한다.
     *
     * <p>주문 정보는 서버가 조회한 신뢰 데이터라 울타리 밖에 두고, 제목·본문은 고객이 쓴 것이므로
     * 울타리 안에 넣는다. 이 구분이 프롬프트 인젝션 방어의 전부이므로 순서를 바꾸지 말 것.
     */
    private String buildInitialMessage(Inquiry inquiry, CheckOrderStatusTool orderTool) {
        StringBuilder sb = new StringBuilder();

        String orderId = inquiry.getRelatedOrderId();
        if (orderId != null && !orderId.isBlank()) {
            try {
                ToolResult orderResult = orderTool.execute(new CheckOrderStatusTool.Input(orderId));
                if (orderResult.ok()) {
                    sb.append("[관련 주문 정보]\n").append(orderResult.data()).append("\n\n");
                } else {
                    sb.append("[관련 주문 조회 실패] ").append(orderResult.errorMessage()).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("[Agent] 주문 정보 선주입 실패 orderId={}", orderId, e);
            }
        }

        sb.append(promptFactory.fenceCustomerText(
                "고객 문의 제목: " + inquiry.getTitle() + "\n\n[문의 내용]\n" + inquiry.getContent()));
        return sb.toString();
    }

    private ToolResult invokeWithInterceptors(
            AgentTool<?> tool, String action, JsonNode actionInput,
            ToolCallContext ctx, Long inquiryId, int step) {

        for (ToolCallInterceptor interceptor : interceptors) {
            Optional<ToolResult> blocked = interceptor.beforeExecute(action, actionInput, ctx);
            if (blocked.isPresent()) {
                log.info("[Agent inquiryId={} step={}] action={} blocked_by={}",
                        inquiryId, step, action, interceptor.getClass().getSimpleName());
                return blocked.get();
            }
        }

        ToolResult result = executeTyped(tool, actionInput, action, inquiryId, step);
        ctx.incrementToolCallCount();

        for (ToolCallInterceptor interceptor : interceptors) {
            result = interceptor.afterExecute(action, actionInput, result, ctx);
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ToolResult executeTyped(AgentTool<?> tool, JsonNode actionInput, String action, Long inquiryId, int step) {
        Object typedInput;
        try {
            typedInput = objectMapper.treeToValue(actionInput, tool.inputType());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.info("[Agent inquiryId={} step={}] action={} input parse failed: {}",
                    inquiryId, step, action, e.getMessage());
            return ToolResult.error(
                    ToolErrorCategory.VALIDATION,
                    false,
                    "Tool input does not match the declared schema: " + e.getMessage());
        }
        try {
            return ((AgentTool) tool).execute(typedInput);
        } catch (Exception e) {
            log.warn("[Agent inquiryId={} step={}] tool error action={}", inquiryId, step, action, e);
            return ToolResult.error(
                    ToolErrorCategory.TRANSIENT,
                    true,
                    "Tool execution failed: " + e.getMessage());
        }
    }

    private String serializeObservation(ToolResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"ok\":false,\"errorCategory\":\"TRANSIENT\",\"isRetryable\":true,"
                    + "\"errorMessage\":\"Failed to serialize tool result\"}";
        }
    }

    private AgentTool<?> resolveTool(List<AgentTool<?>> tools, String name) {
        return tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown tool requested by agent: " + name));
    }

    private AgentResult.FinalAnswer buildFinalAnswer(
            JsonNode node, List<AgentStep> steps, List<RetrievedManualChunkDto> chunks, int totalTokens) {
        return new AgentResult.FinalAnswer(
                requiredText(node, "finalAnswer"),
                validCategory(requiredText(node, "category")),
                validUrgency(requiredText(node, "urgency")),
                node.path("needsHumanReview").asBoolean(true),
                node.path("needsEscalation").asBoolean(false),
                node.path("fraudRiskFlag").asBoolean(false),
                node.path("reason").asText(""),
                List.copyOf(steps),
                chunks,
                totalTokens
        );
    }

    private JsonNode parseJson(String response) {
        try {
            return objectMapper.readTree(stripMarkdownFence(response));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse agent response: " + response, e);
        }
    }

    private String stripMarkdownFence(String response) {
        String trimmed = response.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    /**
     * enum에 없는 값이면 GENERAL로 낮춘다. 하위 레이어의 {@code valueOf}가 던지면 분석 전체가
     * 실패하는데, 분류를 하나 틀리는 것보다 문의를 잃는 게 나쁘다.
     */
    private String validCategory(String raw) {
        try {
            return InquiryCategory.valueOf(raw).name();
        } catch (IllegalArgumentException e) {
            log.warn("[Agent] 알 수 없는 category={} → GENERAL로 대체", raw);
            return InquiryCategory.GENERAL.name();
        }
    }

    private String validUrgency(String raw) {
        try {
            return UrgencyLevel.valueOf(raw).name();
        } catch (IllegalArgumentException e) {
            log.warn("[Agent] 알 수 없는 urgency={} → MEDIUM으로 대체", raw);
            return UrgencyLevel.MEDIUM.name();
        }
    }

    private String requiredText(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Agent final result missing field: " + fieldName);
        }
        return value;
    }
}
