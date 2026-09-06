package com.aicsassistant.analysis.agent;

import com.aicsassistant.analysis.agent.tool.CheckOrderStatusTool;
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
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.trace.Tracer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>이 클래스는 <b>그 선택을 읽고 다음 라운드를 만드는 일만</b> 한다. 주변 관심사는 각각 나가 있다.
 * <ul>
 *   <li>{@link AgentToolFactory} — 이번 실행이 쓸 도구를 만든다. 루프는 무엇을 조회하는지 모른다</li>
 *   <li>{@link ToolInvoker} — 인터셉터 정책 아래 도구를 실행한다 (ADR 0003)</li>
 *   <li>{@link AgentResponseParser} — 모델 출력을 얼마나 관대하게 받아줄지 정한다 (ADR 0006)</li>
 *   <li>{@link AgentTrace} — 무엇을 관측할지 정한다</li>
 * </ul>
 * 넷 다 루프 안에 있을 때는 "지금 읽는 코드가 에이전트의 판단인지 그 주변 배관인지" 가 섞였다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryAgentService {

    private static final int MAX_STEPS = 8;

    private final LlmClient llmClient;
    private final PromptFactory promptFactory;
    private final AgentToolFactory toolFactory;
    private final ToolInvoker toolInvoker;
    private final AgentResponseParser parser;
    private final Tracer tracer;

    /**
     * @param inquiry             분석할 문의
     * @param conversationHistory 이전 대화 메시지 (최초 분석 시 빈 리스트)
     */
    public AgentResult run(Inquiry inquiry, List<InquiryMessage> conversationHistory) {
        AgentToolFactory.Toolset toolset = toolFactory.createFor(inquiry);

        try (AgentTrace trace = AgentTrace.start(tracer, inquiry)) {
            try {
                return runAgentLoop(inquiry, conversationHistory, toolset, trace);
            } catch (RuntimeException e) {
                trace.recordError(e);
                throw e;
            }
        }
    }

    private AgentResult runAgentLoop(
            Inquiry inquiry,
            List<InquiryMessage> conversationHistory,
            AgentToolFactory.Toolset toolset,
            AgentTrace trace) {

        List<AgentTool<?>> tools = toolset.all();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(promptFactory.buildAgentSystemPrompt(tools)));

        ToolCallContext callContext = new ToolCallContext(inquiry.getId(), inquiry.getCustomerIdentifier());

        // 최초 문의 내용 (주문번호가 있으면 주문 정보 선주입)
        messages.add(ChatMessage.user(buildInitialMessage(inquiry, toolset, callContext)));

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

        for (int step = 0; step < MAX_STEPS; step++) {
            try (AgentTrace.StepSpan stepSpan = trace.step(step)) {
                LlmResponse llmResponse = llmClient.completeWithUsage(messages);
                totalTokens += llmResponse.totalTokens();
                String raw = llmResponse.content();
                log.debug("[Agent inquiryId={} step={} tokens={}] raw={}",
                        inquiry.getId(), step, llmResponse.totalTokens(), raw);

                JsonNode node = parser.parse(raw);
                String thought = node.path("thought").asText("");

                if (node.has("finalAnswer")) {
                    log.info("[Agent done] inquiryId={} steps={} totalTokens={}", inquiry.getId(), step, totalTokens);
                    AgentResult.FinalAnswer result = buildFinalAnswer(
                            node, steps, toolset.manual().getCollectedChunks(), totalTokens, callContext);
                    trace.recordAnswer(result, AgentTrace.OUTCOME_FINAL_ANSWER, totalTokens, step + 1);
                    return result;
                }

                if (node.has("followUpQuestion")) {
                    String question = node.path("followUpQuestion").asText("").strip();
                    log.info("[Agent followUp] inquiryId={} steps={} totalTokens={}", inquiry.getId(), step, totalTokens);
                    trace.recordFollowUp(question, totalTokens, step + 1);
                    return new AgentResult.FollowUpQuestion(question, List.copyOf(steps), totalTokens);
                }

                String action = node.path("action").asText("");
                JsonNode actionInput = node.path("actionInput");
                stepSpan.tool(action);

                ToolResult toolResult = toolInvoker.invoke(tools, action, actionInput, callContext, step);

                String observation = toolInvoker.serializeObservation(toolResult);
                log.info("[Agent inquiryId={} step={}] action={} ok={} category={} observation_len={}",
                        inquiry.getId(), step, action, toolResult.ok(), toolResult.errorCategory(), observation.length());

                // search_manual 스텝에는 이번 호출에서 가져온 문서 목록을 첨부
                List<RetrievedManualChunkDto> stepChunks = toolset.manual().name().equals(action)
                        ? toolset.manual().getLastCallChunks()
                        : List.of();
                steps.add(new AgentStep(thought, action, actionInput.toString(), observation, stepChunks));
                messages.add(ChatMessage.assistant(raw));
                messages.add(ChatMessage.user("Observation:\n" + observation));
            }
        }

        // 스텝 소진 — 여기까지 온 문의가 가장 복잡한 건이므로 실패시키지 않고 답을 뽑아낸다
        return forceFinalAnswerWithoutTools(inquiry, messages, steps, toolset, totalTokens, trace, callContext);
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
            AgentToolFactory.Toolset toolset,
            int totalTokens,
            AgentTrace trace,
            ToolCallContext ctx) {

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
            JsonNode node = parser.parse(response.content());
            if (node.has("finalAnswer")) {
                answer = buildFinalAnswer(node, steps, toolset.manual().getCollectedChunks(), tokens, ctx)
                        .withHumanReview();
            }
        } catch (RuntimeException e) {
            log.warn("[Agent] 강제 finalAnswer 라운드 실패 inquiryId={}", inquiry.getId(), e);
        }

        boolean synthetic = answer == null;
        if (synthetic) {
            answer = syntheticBriefing(inquiry, steps, toolset.manual().getCollectedChunks(), tokens);
        }

        log.info("[Agent forced final] inquiryId={} steps={} totalTokens={} synthetic={}",
                inquiry.getId(), steps.size(), tokens, synthetic);
        trace.recordAnswer(
                answer,
                synthetic ? AgentTrace.OUTCOME_FORCED_SYNTHETIC : AgentTrace.OUTCOME_FORCED_FINAL,
                tokens,
                steps.size());
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

    /**
     * 최초 사용자 메시지를 조립한다.
     *
     * <p>주문 정보는 서버가 조회한 신뢰 데이터라 울타리 밖에 두고, 제목·본문은 고객이 쓴 것이므로
     * 울타리 안에 넣는다. 이 구분이 프롬프트 인젝션 방어의 전부이므로 순서를 바꾸지 말 것.
     */
    private String buildInitialMessage(Inquiry inquiry, AgentToolFactory.Toolset toolset, ToolCallContext ctx) {
        StringBuilder sb = new StringBuilder();

        // 모델에게는 시간 감각이 없다. 오늘을 알려주지 않으면 "도착예정: 2026-09-03" 이 과거인지
        // 미래인지 판단할 수 없어, 이미 지난 예정일을 "9월 3일에 배송될 예정입니다" 라고
        // 미래형으로 답하는 일이 실제로 있었다. 주문 mock 이 KST 기준이므로 같은 기준을 쓴다.
        // 시스템 프롬프트가 아니라 이 사용자 메시지에 넣는다 — 캐시되는 접두부를 날짜로 깨지 않기 위해서다.
        sb.append("[오늘] ").append(LocalDate.now(InMemoryOrderRepository.KST)).append("\n\n");

        String orderId = inquiry.getRelatedOrderId();
        if (orderId != null && !orderId.isBlank()) {
            try {
                ToolResult orderResult = toolset.order().execute(new CheckOrderStatusTool.Input(orderId));
                if (orderResult.ok()) {
                    // 인터셉터를 지나가지 않는 경로라 여기서 직접 기록한다.
                    // 문의의 relatedOrderId 이고 소유자 검증을 통과한 고객 자기 주문이라 정당하다.
                    ctx.recordObservedOrder(orderId);
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

    private AgentResult.FinalAnswer buildFinalAnswer(
            JsonNode node, List<AgentStep> steps, List<RetrievedManualChunkDto> chunks,
            int totalTokens, ToolCallContext ctx) {
        AgentResult.FinalAnswer answer = new AgentResult.FinalAnswer(
                parser.requiredText(node, "finalAnswer"),
                parser.validCategory(parser.requiredText(node, "category")),
                parser.validUrgency(parser.requiredText(node, "urgency")),
                node.path("needsHumanReview").asBoolean(true),
                node.path("needsEscalation").asBoolean(false),
                node.path("fraudRiskFlag").asBoolean(false),
                node.path("reason").asText(""),
                List.copyOf(steps),
                chunks,
                totalTokens
        );
        // 제안이 접수된 실행은 무조건 상담사가 본다 — 프롬프트 지시에 맡기지 않는다
        return ctx.stagedChange() ? answer.withHumanReview() : answer;
    }
}
