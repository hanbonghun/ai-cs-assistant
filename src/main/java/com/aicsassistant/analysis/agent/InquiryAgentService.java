package com.aicsassistant.analysis.agent;

import com.aicsassistant.analysis.agent.tool.CheckOrderStatusTool;
import com.aicsassistant.analysis.agent.tool.SearchManualTool;
import com.aicsassistant.analysis.application.ManualRetrievalService;
import com.aicsassistant.analysis.application.PromptFactory;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.infra.llm.ChatMessage;
import com.aicsassistant.analysis.infra.llm.LlmClient;
import com.aicsassistant.analysis.infra.llm.LlmResponse;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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

    private final LlmClient llmClient;
    private final ManualRetrievalService manualRetrievalService;
    private final PromptFactory promptFactory;
    private final ObjectMapper objectMapper;
    private final InMemoryOrderRepository orderRepository;

    /**
     * @param inquiry         분석할 문의
     * @param conversationHistory 이전 대화 메시지 (최초 분석 시 빈 리스트)
     */
    public AgentResult run(Inquiry inquiry, List<InquiryMessage> conversationHistory) {
        CheckOrderStatusTool orderTool = new CheckOrderStatusTool(orderRepository);
        SearchManualTool searchTool = new SearchManualTool(manualRetrievalService);
        List<AgentTool> tools = List.of(searchTool, orderTool);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(promptFactory.buildAgentSystemPrompt(tools)));

        // 최초 문의 내용 (주문번호가 있으면 주문 정보 선주입)
        messages.add(ChatMessage.user(buildInitialMessage(inquiry, orderTool)));

        // 이전 대화 히스토리 주입 (CUSTOMER → user, AI → assistant)
        for (InquiryMessage msg : conversationHistory) {
            if (msg.getRole() == InquiryMessageRole.AI) {
                messages.add(ChatMessage.assistant(msg.getContent()));
            } else {
                messages.add(ChatMessage.user(msg.getContent()));
            }
        }

        List<AgentStep> steps = new ArrayList<>();
        int totalTokens = 0;

        for (int step = 0; step < MAX_STEPS; step++) {
            LlmResponse llmResponse = llmClient.completeWithUsage(messages);
            totalTokens += llmResponse.totalTokens();
            String raw = llmResponse.content();
            log.debug("[Agent inquiryId={} step={} tokens={}] raw={}", inquiry.getId(), step, llmResponse.totalTokens(), raw);

            JsonNode node = parseJson(raw);
            String thought = node.path("thought").asText("");

            if (node.has("finalAnswer")) {
                log.info("[Agent done] inquiryId={} steps={} totalTokens={}", inquiry.getId(), step, totalTokens);
                return buildFinalAnswer(node, steps, searchTool.getCollectedChunks(), totalTokens);
            }

            if (node.has("followUpQuestion")) {
                String question = node.path("followUpQuestion").asText("").strip();
                log.info("[Agent followUp] inquiryId={} steps={} totalTokens={}", inquiry.getId(), step, totalTokens);
                return new AgentResult.FollowUpQuestion(question, List.copyOf(steps), totalTokens);
            }

            String action = node.path("action").asText("");
            JsonNode actionInput = node.path("actionInput");

            AgentTool tool = resolveTool(tools, action);
            ToolResult toolResult;
            try {
                toolResult = tool.execute(actionInput);
            } catch (Exception e) {
                toolResult = ToolResult.error(
                        ToolErrorCategory.TRANSIENT,
                        true,
                        "Tool execution failed: " + e.getMessage());
                log.warn("[Agent inquiryId={} step={}] tool error action={}", inquiry.getId(), step, action, e);
            }

            String observation = serializeObservation(toolResult);
            log.info("[Agent inquiryId={} step={}] action={} ok={} category={} observation_len={}",
                    inquiry.getId(), step, action, toolResult.ok(), toolResult.errorCategory(), observation.length());

            // search_manual 스텝에는 이번 호출에서 가져온 문서 목록을 첨부
            List<RetrievedManualChunkDto> stepChunks = (tool instanceof SearchManualTool s) ? s.getLastCallChunks() : List.of();
            steps.add(new AgentStep(thought, action, actionInput.toString(), observation, stepChunks));
            messages.add(ChatMessage.assistant(raw));
            messages.add(ChatMessage.user("Observation:\n" + observation));
        }

        throw new IllegalStateException(
                "Agent exceeded maximum steps (" + MAX_STEPS + ") for inquiryId=" + inquiry.getId());
    }

    private String buildInitialMessage(Inquiry inquiry, CheckOrderStatusTool orderTool) {
        StringBuilder sb = new StringBuilder();
        sb.append("고객 문의 제목: ").append(inquiry.getTitle()).append("\n\n");

        String orderId = inquiry.getRelatedOrderId();
        if (orderId != null && !orderId.isBlank()) {
            try {
                JsonNode orderInput = objectMapper.createObjectNode().put("orderId", orderId);
                ToolResult orderResult = orderTool.execute(orderInput);
                if (orderResult.ok()) {
                    sb.append("[관련 주문 정보]\n").append(orderResult.data()).append("\n");
                } else {
                    sb.append("[관련 주문 조회 실패] ").append(orderResult.errorMessage()).append("\n");
                }
            } catch (Exception e) {
                log.warn("[Agent] 주문 정보 선주입 실패 orderId={}", orderId, e);
            }
        }

        sb.append("[문의 내용]\n").append(inquiry.getContent());
        return sb.toString();
    }

    private String serializeObservation(ToolResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"ok\":false,\"errorCategory\":\"TRANSIENT\",\"isRetryable\":true,"
                    + "\"errorMessage\":\"Failed to serialize tool result\"}";
        }
    }

    private AgentTool resolveTool(List<AgentTool> tools, String name) {
        return tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown tool requested by agent: " + name));
    }

    private AgentResult.FinalAnswer buildFinalAnswer(
            JsonNode node, List<AgentStep> steps, List<RetrievedManualChunkDto> chunks, int totalTokens) {
        return new AgentResult.FinalAnswer(
                requiredText(node, "finalAnswer"),
                requiredText(node, "category"),
                requiredText(node, "urgency"),
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

    private String requiredText(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Agent final result missing field: " + fieldName);
        }
        return value;
    }
}
