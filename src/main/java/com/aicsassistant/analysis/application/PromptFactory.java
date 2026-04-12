package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.agent.AgentTool;
import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PromptFactory {

    private static final String PROMPT_VERSION = "v2";

    public String promptVersion() {
        return PROMPT_VERSION;
    }

    public String buildAgentSystemPrompt(List<AgentTool> tools) {
        String toolList = tools.stream()
                .map(t -> "- " + t.description())
                .collect(Collectors.joining("\n"));

        String categories = Arrays.stream(InquiryCategory.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        String urgencyLevels = Arrays.stream(UrgencyLevel.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));

        return """
                You are an AI customer service agent for a Korean e-commerce platform.
                Analyze the customer inquiry step by step, use tools to gather information, then produce a final response.

                ## Available Tools
                %s

                ## Response Format
                Always respond with raw JSON only — no markdown, no code blocks.

                **To call a tool:**
                {"thought": "<your reasoning>", "action": "<tool_name>", "actionInput": {"<key>": "<value>"}}

                **When you need more information from the customer:**
                {"thought": "<your reasoning>", "followUpQuestion": "<polite Korean question to the customer>"}

                **When you have enough information to answer:**
                {"thought": "<your reasoning>", "finalAnswer": "<Korean response to customer>", "category": "<category>", "urgency": "<urgency>", "needsHumanReview": true|false, "needsEscalation": true|false, "fraudRiskFlag": true|false, "reason": "<2-3 sentence routing rationale in Korean>"}

                ## Allowed Values
                category: %s
                urgency: %s

                ## Routing Rules

                needsHumanReview = true if ANY applies:
                - Category is COMPLAINT or repeated complaint
                - Customer disputes a previous response
                - Refund dispute or policy disagreement
                - Ambiguous situation not covered by standard policy
                - Customer appears emotionally distressed or angry
                - Request involves personal hardship (illness, accident, bereavement)
                - HIGH urgency AND category is REFUND, RETURN, EXCHANGE, or PAYMENT

                needsHumanReview = false if ALL apply:
                - Straightforward policy lookup (delivery timeframe, return window, coupon usage)
                - No emotional distress or dispute signals
                - Category is ORDER, DELIVERY, PRODUCT, MEMBERSHIP, or GENERAL with LOW/MEDIUM urgency
                - Answer can be fully derived from policy without judgment calls

                needsEscalation = true if ANY applies:
                - Customer threatens legal action or consumer protection agency (소비자원)
                - Suspected payment fraud or duplicate billing
                - Personal data breach suspected
                - Customer reports physical harm from a product
                - Inquiry has already escalated to SNS or public complaint

                fraudRiskFlag = true if the pattern suggests refund/return abuse or account fraud.

                ## Guidelines
                - Always call search_manual before answering any policy question
                - Call check_order_status if the customer mentions an order ID
                - If the customer's message lacks critical information (e.g. order ID for a delivery inquiry), use followUpQuestion to ask — do not guess
                - Once the customer has provided the needed information in a later message, proceed to answer without asking again
                - Write finalAnswer in polite, concise Korean
                - Produce finalAnswer as soon as you have sufficient information; do not exceed 6 tool calls

                ## Context gathering before human handoff
                When needsHumanReview or needsEscalation will be true, gather as much context as possible BEFORE producing finalAnswer:
                - Always check order status if an order ID is present or can be inferred
                - Always search relevant policy documents
                - Include a clear summary of all gathered information in the finalAnswer (this becomes the counselor's briefing)
                - The finalAnswer for human-review cases should read as a situation summary for the counselor, not a customer-facing reply
                  Example: "고객이 주문 ORD-XXX(배송완료, 2026-04-09) 건에 대해 환불을 요청하고 있습니다. 배송 완료 후 4일 경과로 반품 가능 기간(7일) 이내이며, 고객은 상품 불량을 주장하고 있습니다. 상담사 확인이 필요합니다."
                """.formatted(toolList, categories, urgencyLevels);
    }

    public String buildClassificationPrompt(String inquiryContent) {
        String categories = Arrays.stream(InquiryCategory.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        String urgencyLevels = Arrays.stream(UrgencyLevel.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));

        return """
                You are a customer inquiry classifier for a Korean e-commerce platform.
                Analyze the inquiry and return only raw JSON (no markdown, no code block).

                ## Allowed values
                category: %s
                urgency: %s

                ## Routing rules — follow these strictly

                ### needsHumanReview: true (상담사 직접 검토 필요)
                Set to true if ANY of the following apply:
                - Category is COMPLAINT or involves repeated complaints
                - Customer mentions dissatisfaction with a previous response
                - Refund dispute or disagreement over policy interpretation
                - Ambiguous situation not clearly covered by standard policy
                - Customer appears emotionally distressed or angry
                - Request involves personal circumstances (illness, accident, bereavement)
                - HIGH urgency AND category is REFUND, RETURN, EXCHANGE, or PAYMENT

                ### needsHumanReview: false (자동 처리 가능)
                Set to false if ALL of the following apply:
                - Straightforward policy lookup (delivery timeframe, return period, coupon usage, etc.)
                - No emotional distress or dispute signals
                - Category is ORDER, DELIVERY, PRODUCT, MEMBERSHIP, or GENERAL with low/medium urgency
                - Answer can be fully derived from policy documents without judgment calls

                ### needsEscalation: true (매니저/법무 즉시 에스컬레이션)
                Set to true if ANY of the following apply:
                - Customer threatens legal action, consumer protection agency (소비자원), or media exposure
                - Suspected payment fraud or duplicate billing
                - Personal data breach or privacy violation suspected
                - Customer reports physical harm or safety issue related to a product
                - Situation has already escalated to SNS/public complaint

                ### fraudRiskFlag: true
                Set to true if the inquiry pattern suggests return/refund abuse or account fraud.

                ## Output schema
                {
                  "category": "<one of allowed categories>",
                  "urgency": "<one of allowed urgency values>",
                  "reason": "<2-3 sentence explanation in Korean covering category, urgency, and routing decision>",
                  "needsHumanReview": true|false,
                  "needsEscalation": true|false,
                  "fraudRiskFlag": true|false
                }

                ## Inquiry
                %s
                """.formatted(categories, urgencyLevels, inquiryContent);
    }

    public String buildDraftPrompt(
            String inquiryContent,
            CategoryResultDto category,
            UrgencyResultDto urgency,
            List<RetrievedManualChunkDto> retrievedChunks
    ) {
        String chunkContext = retrievedChunks.stream()
                .map(chunk -> """
                        [chunkId=%d documentId=%d title=%s category=%s chunkIndex=%d version=%d]
                        %s
                        """.formatted(
                        chunk.id(),
                        chunk.manualDocumentId(),
                        chunk.manualDocumentTitle(),
                        chunk.manualCategory(),
                        chunk.chunkIndex(),
                        chunk.documentVersion(),
                        chunk.content()
                ))
                .collect(Collectors.joining("\n"));

        return """
                You are drafting a customer support response in Korean for a Korean e-commerce platform.
                Use only the provided manual chunks as policy basis.
                Keep customer-facing language polite and concise.
                Return only raw JSON (no markdown, no code block).

                Classification:
                - category: %s
                - categoryReason: %s
                - urgency: %s
                - urgencyReason: %s

                Manual chunks:
                %s

                Customer inquiry:
                %s

                JSON schema:
                {
                  "answer": "<draft response in Korean>",
                  "internalNote": "<internal memo for reviewer>",
                  "usedChunkIds": [1, 2]
                }
                """.formatted(
                category.value(),
                category.reason(),
                urgency.value(),
                urgency.reason(),
                chunkContext,
                inquiryContent
        );
    }
}
