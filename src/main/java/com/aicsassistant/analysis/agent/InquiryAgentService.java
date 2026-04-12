package com.aicsassistant.analysis.agent;

import com.aicsassistant.analysis.agent.tool.CheckOrderStatusTool;
import com.aicsassistant.analysis.agent.tool.SearchManualTool;
import com.aicsassistant.analysis.application.ManualRetrievalService;
import com.aicsassistant.analysis.application.PromptFactory;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.infra.llm.ChatMessage;
import com.aicsassistant.analysis.infra.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aicsassistant.inquiry.domain.Inquiry;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ReAct (Reasoning + Acting) agent that drives multi-step inquiry analysis.
 *
 * <p>Each iteration the LLM either calls a tool or produces a finalAnswer.
 * Tool results are fed back as observations until the agent terminates or
 * exceeds {@link #MAX_STEPS}.</p>
 */
@Service
public class InquiryAgentService {

    private static final Logger log = LoggerFactory.getLogger(InquiryAgentService.class);
    private static final int MAX_STEPS = 8;

    private final LlmClient llmClient;
    private final ManualRetrievalService manualRetrievalService;
    private final PromptFactory promptFactory;
    private final ObjectMapper objectMapper;

    public InquiryAgentService(
            LlmClient llmClient,
            ManualRetrievalService manualRetrievalService,
            PromptFactory promptFactory,
            ObjectMapper objectMapper
    ) {
        this.llmClient = llmClient;
        this.manualRetrievalService = manualRetrievalService;
        this.promptFactory = promptFactory;
        this.objectMapper = objectMapper;
    }

    public AgentFinalResult run(Inquiry inquiry) {
        // Tools are created fresh per run — each run has its own state (collected chunks)
        SearchManualTool searchTool = new SearchManualTool(manualRetrievalService);
        List<AgentTool> tools = List.of(searchTool, new CheckOrderStatusTool());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(promptFactory.buildAgentSystemPrompt(tools)));
        messages.add(ChatMessage.user("고객 문의 제목: " + inquiry.getTitle() + "\n\n" + inquiry.getContent()));

        List<AgentStep> steps = new ArrayList<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            String raw = llmClient.complete(messages);
            log.debug("[Agent inquiryId={} step={}] raw={}", inquiry.getId(), step, raw);

            JsonNode node = parseJson(raw);
            String thought = node.path("thought").asText("");

            if (node.has("finalAnswer")) {
                log.info("[Agent done] inquiryId={} steps={}", inquiry.getId(), step);
                return buildFinalResult(node, steps, searchTool.getCollectedChunks());
            }

            String action = node.path("action").asText("");
            JsonNode actionInput = node.path("actionInput");

            AgentTool tool = resolveTool(tools, action);
            String observation;
            try {
                observation = tool.execute(actionInput);
            } catch (Exception e) {
                observation = "Tool execution error: " + e.getMessage();
                log.warn("[Agent inquiryId={} step={}] tool error action={}", inquiry.getId(), step, action, e);
            }

            log.info("[Agent inquiryId={} step={}] action={} observation_len={}",
                    inquiry.getId(), step, action, observation.length());

            steps.add(new AgentStep(thought, action, actionInput.toString(), observation));

            messages.add(ChatMessage.assistant(raw));
            messages.add(ChatMessage.user("Observation:\n" + observation));
        }

        throw new IllegalStateException(
                "Agent exceeded maximum steps (" + MAX_STEPS + ") for inquiryId=" + inquiry.getId());
    }

    private AgentTool resolveTool(List<AgentTool> tools, String name) {
        return tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown tool requested by agent: " + name));
    }

    private AgentFinalResult buildFinalResult(
            JsonNode node, List<AgentStep> steps, List<RetrievedManualChunkDto> chunks) {
        return new AgentFinalResult(
                requiredText(node, "finalAnswer"),
                requiredText(node, "category"),
                requiredText(node, "urgency"),
                node.path("needsHumanReview").asBoolean(true),
                node.path("needsEscalation").asBoolean(false),
                node.path("fraudRiskFlag").asBoolean(false),
                node.path("reason").asText(""),
                List.copyOf(steps),
                chunks
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
