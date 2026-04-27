package com.aicsassistant.analysis.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.aicsassistant.analysis.application.ManualRetrievalService;
import com.aicsassistant.analysis.application.PromptFactory;
import com.aicsassistant.analysis.infra.llm.LlmClient;
import com.aicsassistant.analysis.infra.llm.LlmResponse;
import com.aicsassistant.faq.InMemoryFaqRepository;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InquiryAgentServiceTest {

    @Mock LlmClient llmClient;
    @Mock ManualRetrievalService manualRetrievalService;
    @Mock PromptFactory promptFactory;

    InquiryAgentService agentService;

    @BeforeEach
    void setUp() {
        when(promptFactory.buildAgentSystemPrompt(anyList())).thenReturn("system prompt");
        agentService = new InquiryAgentService(
                llmClient,
                manualRetrievalService,
                promptFactory,
                new ObjectMapper(),
                new InMemoryOrderRepository(),
                new InMemoryFaqRepository(),
                List.of()
        );
    }

    @Test
    void returnsFinalAnswerOnFirstStep() {
        givenLlmResponds(finalAnswer("환불은 3일 이내 처리됩니다.", "REFUND", "LOW", false));

        AgentResult result = agentService.run(inquiry("환불 문의"), List.of());

        assertThat(result).isInstanceOf(AgentResult.FinalAnswer.class);
        AgentResult.FinalAnswer answer = (AgentResult.FinalAnswer) result;
        assertThat(answer.answer()).isEqualTo("환불은 3일 이내 처리됩니다.");
        assertThat(answer.category()).isEqualTo("REFUND");
        assertThat(answer.needsHumanReview()).isFalse();
    }

    @Test
    void returnsFinalAnswerAfterToolCall() {
        givenLlmResponds(
                toolCall("search_manual", "{\"query\":\"환불 정책\"}"),
                finalAnswer("환불 정책에 따르면 ...", "REFUND", "MEDIUM", true)
        );
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of());

        AgentResult result = agentService.run(inquiry("환불 가능한가요?"), List.of());

        assertThat(result).isInstanceOf(AgentResult.FinalAnswer.class);
        AgentResult.FinalAnswer answer = (AgentResult.FinalAnswer) result;
        assertThat(answer.steps()).hasSize(1);
        assertThat(answer.steps().get(0).action()).isEqualTo("search_manual");
    }

    @Test
    void returnsFollowUpQuestionWhenInfoInsufficient() {
        givenLlmResponds(followUpQuestion("주문 번호를 알려주실 수 있나요?"));

        AgentResult result = agentService.run(inquiry("배송이 왜 이러나요?"), List.of());

        assertThat(result).isInstanceOf(AgentResult.FollowUpQuestion.class);
        AgentResult.FollowUpQuestion followUp = (AgentResult.FollowUpQuestion) result;
        assertThat(followUp.question()).isEqualTo("주문 번호를 알려주실 수 있나요?");
    }

    @Test
    void stripsMarkdownFenceFromLlmResponse() {
        givenLlmResponds("```json\n" + finalAnswer("마크다운 제거 테스트", "GENERAL", "LOW", false) + "\n```");

        AgentResult result = agentService.run(inquiry("일반 문의"), List.of());

        assertThat(result).isInstanceOf(AgentResult.FinalAnswer.class);
        assertThat(((AgentResult.FinalAnswer) result).answer()).isEqualTo("마크다운 제거 테스트");
    }

    @Test
    void throwsWhenMaxStepsExceeded() {
        // 툴 호출만 계속 반복해서 MAX_STEPS 초과
        String infiniteToolCall = toolCall("search_manual", "{\"query\":\"반복\"}");
        when(llmClient.completeWithUsage(anyList()))
                .thenReturn(new LlmResponse(infiniteToolCall, 10, 20));
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of());

        assertThatThrownBy(() -> agentService.run(inquiry("무한 루프 문의"), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maximum steps");
    }

    @Test
    void interceptorCanBlockToolCallBeforeExecution() {
        InquiryAgentService serviceWithBlocker = new InquiryAgentService(
                llmClient, manualRetrievalService, promptFactory, new ObjectMapper(),
                new InMemoryOrderRepository(),
                new InMemoryFaqRepository(),
                List.of(new com.aicsassistant.analysis.agent.ToolCallInterceptor() {
                    @Override
                    public java.util.Optional<com.aicsassistant.analysis.agent.ToolResult> beforeExecute(
                            String toolName, com.fasterxml.jackson.databind.JsonNode input,
                            com.aicsassistant.analysis.agent.ToolCallContext ctx) {
                        return java.util.Optional.of(com.aicsassistant.analysis.agent.ToolResult.error(
                                com.aicsassistant.analysis.agent.ToolErrorCategory.PERMISSION,
                                false,
                                "blocked-by-test"));
                    }
                }));
        givenLlmResponds(
                toolCall("search_manual", "{\"query\":\"환불\"}"),
                finalAnswer("권한 부족으로 상담사에게 라우팅합니다.", "REFUND", "MEDIUM", true)
        );

        AgentResult result = serviceWithBlocker.run(inquiry("환불 문의"), List.of());

        AgentStep step = ((AgentResult.FinalAnswer) result).steps().get(0);
        assertThat(step.observation())
                .contains("\"errorCategory\":\"PERMISSION\"")
                .contains("blocked-by-test");
    }

    @Test
    void interceptorCanModifyResultAfterExecution() {
        InquiryAgentService serviceWithDecorator = new InquiryAgentService(
                llmClient, manualRetrievalService, promptFactory, new ObjectMapper(),
                new InMemoryOrderRepository(),
                new InMemoryFaqRepository(),
                List.of(new com.aicsassistant.analysis.agent.ToolCallInterceptor() {
                    @Override
                    public com.aicsassistant.analysis.agent.ToolResult afterExecute(
                            String toolName, com.fasterxml.jackson.databind.JsonNode input,
                            com.aicsassistant.analysis.agent.ToolResult result,
                            com.aicsassistant.analysis.agent.ToolCallContext ctx) {
                        return com.aicsassistant.analysis.agent.ToolResult.success(
                                (result.data() == null ? "" : result.data()) + "\n[GUARD]");
                    }
                }));
        givenLlmResponds(
                toolCall("search_manual", "{\"query\":\"환불\"}"),
                finalAnswer("ok", "GENERAL", "LOW", false)
        );
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of());

        AgentResult result = serviceWithDecorator.run(inquiry("환불"), List.of());

        AgentStep step = ((AgentResult.FinalAnswer) result).steps().get(0);
        assertThat(step.observation()).contains("[GUARD]");
    }

    @Test
    void faqMissThenFallsBackToManualSearch() {
        // 가이드 시나리오: search_faq가 NOT_FOUND를 반환하면 LLM이 search_manual로 폴백해야 함
        givenLlmResponds(
                toolCall("search_faq", "{\"question\":\"우주선 발사 절차\"}"),
                toolCall("search_manual", "{\"query\":\"우주선 발사\"}"),
                finalAnswer("죄송합니다, 관련 정책을 찾지 못했습니다.", "GENERAL", "LOW", true)
        );
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of());

        AgentResult result = agentService.run(inquiry("우주선 발사 절차 알려주세요"), List.of());

        AgentResult.FinalAnswer answer = (AgentResult.FinalAnswer) result;
        assertThat(answer.steps()).hasSize(2);
        assertThat(answer.steps().get(0).action()).isEqualTo("search_faq");
        assertThat(answer.steps().get(0).observation())
                .contains("\"errorCategory\":\"NOT_FOUND\"")
                .contains("search_manual");
        assertThat(answer.steps().get(1).action()).isEqualTo("search_manual");
    }

    @Test
    void faqAnswersCommonQuestionWithoutManualFallback() {
        // 단순 FAQ는 search_faq 한 번으로 처리, search_manual 호출 없이 finalAnswer
        givenLlmResponds(
                toolCall("search_faq", "{\"question\":\"환불 며칠 걸려요?\"}"),
                finalAnswer("환불은 영업일 2~3일 내에 처리됩니다.", "REFUND", "LOW", false)
        );

        AgentResult result = agentService.run(inquiry("환불 며칠 걸려요?"), List.of());

        AgentResult.FinalAnswer answer = (AgentResult.FinalAnswer) result;
        assertThat(answer.steps()).hasSize(1);
        assertThat(answer.steps().get(0).action()).isEqualTo("search_faq");
        assertThat(answer.steps().get(0).observation()).contains("\"ok\":true");
    }

    @Test
    void mapsSchemaMismatchToValidationError() {
        // LLM이 actionInput을 객체가 아닌 문자열로 보내면(record 역직렬화 실패) VALIDATION 에러로 매핑되어야 함
        givenLlmResponds(
                toolCall("search_manual", "\"환불\""),
                finalAnswer("입력 형식 오류로 답변이 어렵습니다.", "GENERAL", "LOW", true)
        );

        AgentResult result = agentService.run(inquiry("환불 문의"), List.of());

        AgentResult.FinalAnswer answer = (AgentResult.FinalAnswer) result;
        AgentStep step = answer.steps().get(0);
        assertThat(step.observation())
                .contains("\"ok\":false")
                .contains("\"errorCategory\":\"VALIDATION\"")
                .contains("does not match the declared schema");
    }

    @Test
    void unknownInputFieldIsIgnoredAndDelegatesToToolValidation() {
        // 잘못된 필드명(query 대신 q)은 Jackson이 무시 → query=null → 도구 자체 검증이 VALIDATION 에러 반환
        givenLlmResponds(
                toolCall("search_manual", "{\"q\":\"환불\"}"),
                finalAnswer("필드 누락으로 답변이 어렵습니다.", "GENERAL", "LOW", true)
        );

        AgentResult result = agentService.run(inquiry("환불 문의"), List.of());

        AgentStep step = ((AgentResult.FinalAnswer) result).steps().get(0);
        assertThat(step.observation())
                .contains("\"errorCategory\":\"VALIDATION\"")
                .contains("query");
    }

    @Test
    void wrapsToolExceptionAsTransientObservation() {
        givenLlmResponds(
                toolCall("search_manual", "{\"query\":\"환불\"}"),
                finalAnswer("일시적 오류로 답변이 어렵습니다.", "GENERAL", "LOW", true)
        );
        when(manualRetrievalService.retrieve(any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        AgentResult result = agentService.run(inquiry("환불 문의"), List.of());

        AgentResult.FinalAnswer answer = (AgentResult.FinalAnswer) result;
        assertThat(answer.steps()).hasSize(1);
        AgentStep step = answer.steps().get(0);
        assertThat(step.observation())
                .contains("\"ok\":false")
                .contains("\"errorCategory\":\"TRANSIENT\"")
                .contains("\"isRetryable\":true")
                .contains("DB connection lost");
    }

    @Test
    void multiConcernAllAutoAnswerable_callsBothToolsAndProducesUnifiedAnswer() {
        // 케이스 1: "ORD 배송 조회 + 반품 정책" — 두 도구 모두 호출 후 통합 답변
        givenLlmResponds(
                toolCall("check_order_status", "{\"orderId\":\"ORD-20260410-001\"}"),
                toolCall("search_manual", "{\"query\":\"반품 정책\"}"),
                finalAnswer(
                        "1) 배송: 4월 13일 도착 예정입니다. 2) 반품 정책: 수령 후 7일 이내 가능합니다.",
                        "DELIVERY", "MEDIUM", false)
        );
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of());

        AgentResult result = agentService.run(
                inquiry("ORD-20260410-001 배송 언제 와요? 그리고 반품 정책 알려주세요"), List.of());

        AgentResult.FinalAnswer answer = (AgentResult.FinalAnswer) result;
        assertThat(answer.steps()).hasSize(2);
        assertThat(answer.steps().get(0).action()).isEqualTo("check_order_status");
        assertThat(answer.steps().get(1).action()).isEqualTo("search_manual");
        assertThat(answer.answer()).contains("배송").contains("반품 정책");
        assertThat(answer.needsHumanReview()).isFalse();
    }

    @Test
    void multiConcernPartialEscalation_answersPolicyAndFlagsRefundForHuman() {
        // 케이스 2: "환불 처리 + 반품 정책" — 정책은 답변, 환불 액션은 needsHumanReview true
        givenLlmResponds(
                toolCall("search_manual", "{\"query\":\"반품 정책\"}"),
                finalAnswer(
                        "1) 반품 정책: 수령 후 7일 이내 가능합니다. 2) 환불 처리: 담당자가 확인 후 처리해 드리겠습니다.",
                        "REFUND", "MEDIUM", true)
        );
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of());

        AgentResult result = agentService.run(
                inquiry("환불 처리해주세요. 그리고 반품 정책도 알려주세요"), List.of());

        AgentResult.FinalAnswer answer = (AgentResult.FinalAnswer) result;
        assertThat(answer.needsHumanReview()).isTrue();
        assertThat(answer.answer())
                .contains("반품 정책")
                .contains("담당자가 확인");
    }

    @Test
    void multiConcernBudgetGuard_summarizesPartialAnswerAndEscalates() {
        // 케이스 3: 예산 가드(6회 한도) 발동 후 부분 답변 + needsHumanReview
        // 인터셉터가 즉시 차단하면 도구는 실행되지 않고 PERMISSION 에러가 observation에 들어감.
        InquiryAgentService budgetExhaustedAgent = new InquiryAgentService(
                llmClient, manualRetrievalService, promptFactory, new ObjectMapper(),
                new InMemoryOrderRepository(),
                new InMemoryFaqRepository(),
                List.of(new com.aicsassistant.analysis.agent.ToolCallInterceptor() {
                    @Override
                    public java.util.Optional<com.aicsassistant.analysis.agent.ToolResult> beforeExecute(
                            String toolName, com.fasterxml.jackson.databind.JsonNode input,
                            com.aicsassistant.analysis.agent.ToolCallContext ctx) {
                        return java.util.Optional.of(com.aicsassistant.analysis.agent.ToolResult.error(
                                com.aicsassistant.analysis.agent.ToolErrorCategory.PERMISSION,
                                false,
                                "Tool call budget exhausted"));
                    }
                }));
        givenLlmResponds(
                toolCall("search_manual", "{\"query\":\"반품 정책\"}"),
                finalAnswer(
                        "1) 반품 정책: 일부 정보만 확인했습니다. 2) 그 외 요청은 담당자가 확인 후 처리해 드리겠습니다.",
                        "RETURN", "MEDIUM", true)
        );

        AgentResult result = budgetExhaustedAgent.run(
                inquiry("반품 정책 알려주고, ORD-A 환불, ORD-B 교환, 적립금 환급도 부탁해요"),
                List.of());

        AgentResult.FinalAnswer answer = (AgentResult.FinalAnswer) result;
        assertThat(answer.needsHumanReview()).isTrue();
        AgentStep guardedStep = answer.steps().get(0);
        assertThat(guardedStep.observation())
                .contains("\"errorCategory\":\"PERMISSION\"")
                .contains("budget exhausted");
        assertThat(answer.answer()).contains("담당자");
    }

    @Test
    void accumulatesTotalTokensAcrossSteps() {
        givenLlmResponds(
                toolCall("search_manual", "{\"query\":\"배송\"}"),
                finalAnswer("배송은 3일 소요됩니다.", "DELIVERY", "LOW", false)
        );
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of());

        AgentResult result = agentService.run(inquiry("배송 문의"), List.of());

        // 각 스텝마다 promptTokens=10, completionTokens=20 → 2스텝 = 60
        assertThat(((AgentResult.FinalAnswer) result).totalTokens()).isEqualTo(60);
    }

    // --- helpers ---

    private void givenLlmResponds(String... responses) {
        var stub = when(llmClient.completeWithUsage(anyList()));
        for (String response : responses) {
            stub = stub.thenReturn(new LlmResponse(response, 10, 20));
        }
    }

    private static String finalAnswer(String answer, String category, String urgency, boolean needsHumanReview) {
        return """
                {"thought":"최종 답변 작성","finalAnswer":"%s","category":"%s","urgency":"%s",\
                "needsHumanReview":%b,"needsEscalation":false,"fraudRiskFlag":false,"reason":"test"}
                """.formatted(answer, category, urgency, needsHumanReview);
    }

    private static String toolCall(String tool, String input) {
        return """
                {"thought":"정보 수집 필요","action":"%s","actionInput":%s}
                """.formatted(tool, input);
    }

    private static String followUpQuestion(String question) {
        return """
                {"thought":"추가 정보 필요","followUpQuestion":"%s"}
                """.formatted(question);
    }

    private static Inquiry inquiry(String content) {
        return Inquiry.create("cust-001", "테스트 문의", content);
    }
}
