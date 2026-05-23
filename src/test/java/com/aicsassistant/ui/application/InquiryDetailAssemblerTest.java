package com.aicsassistant.ui.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aicsassistant.analysis.application.AnalysisLogService;
import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.ui.viewmodel.InquiryDetailViewModel.AgentStepView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InquiryDetailAssemblerTest {

    @Mock
    AnalysisLogService analysisLogService;

    @Mock
    ManualService manualService;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    InquiryDetailAssembler assembler;

    @Test
    void loadAgentSteps_returnsEmpty_whenLogMissing() {
        when(analysisLogService.getLatestAgentStepsJson(1L)).thenReturn(Optional.empty());

        assertThat(assembler.loadAgentSteps(1L)).isEmpty();
    }

    @Test
    void loadAgentSteps_returnsEmpty_whenJsonBlank() {
        when(analysisLogService.getLatestAgentStepsJson(1L)).thenReturn(Optional.of("   "));

        assertThat(assembler.loadAgentSteps(1L)).isEmpty();
    }

    @Test
    void loadAgentSteps_returnsEmpty_onParseError() {
        when(analysisLogService.getLatestAgentStepsJson(1L)).thenReturn(Optional.of("not a json"));

        assertThat(assembler.loadAgentSteps(1L)).isEmpty();
    }

    @Test
    void loadAgentSteps_summarizesObservationTo200Chars() {
        String longObs = "x".repeat(500);
        String stepsJson = """
                [
                  {"thought":"t","action":"search_manual","actionInput":"{}","observation":"%s","referencedChunks":[]}
                ]
                """.formatted(longObs);
        when(analysisLogService.getLatestAgentStepsJson(1L)).thenReturn(Optional.of(stepsJson));

        List<AgentStepView> steps = assembler.loadAgentSteps(1L);

        assertThat(steps).hasSize(1);
        AgentStepView view = steps.get(0);
        assertThat(view.actionLabel()).isEqualTo("정책 문서 검색");
        assertThat(view.observationSummary()).hasSize(200 + "...".length()).endsWith("...");
    }

    @Test
    void loadAgentSteps_extractsDataFromToolResultSuccess() {
        String stepsJson = """
                [{"thought":"t","action":"search_faq","actionInput":"{}",
                  "observation":"{\\"ok\\":true,\\"data\\":\\"Q: 회원 탈퇴는 어떻게 하나요?\\\\nA: 마이페이지에서...\\",\\"errorCategory\\":null,\\"isRetryable\\":false,\\"errorMessage\\":null}",
                  "referencedChunks":[]}]
                """;
        when(analysisLogService.getLatestAgentStepsJson(1L)).thenReturn(Optional.of(stepsJson));

        List<AgentStepView> steps = assembler.loadAgentSteps(1L);

        assertThat(steps).hasSize(1);
        String summary = steps.get(0).observationSummary();
        assertThat(summary)
                .startsWith("Q: 회원 탈퇴는 어떻게 하나요?")
                .doesNotContain("\"ok\"")
                .doesNotContain("errorCategory");
    }

    @Test
    void loadAgentSteps_formatsToolResultErrorAsHumanReadable() {
        String stepsJson = """
                [{"thought":"t","action":"check_order_status","actionInput":"{}",
                  "observation":"{\\"ok\\":false,\\"data\\":null,\\"errorCategory\\":\\"NOT_FOUND\\",\\"isRetryable\\":false,\\"errorMessage\\":\\"주문을 찾을 수 없습니다\\"}",
                  "referencedChunks":[]}]
                """;
        when(analysisLogService.getLatestAgentStepsJson(1L)).thenReturn(Optional.of(stepsJson));

        List<AgentStepView> steps = assembler.loadAgentSteps(1L);

        assertThat(steps.get(0).observationSummary())
                .isEqualTo("❌ NOT_FOUND: 주문을 찾을 수 없습니다");
    }

    @Test
    void loadAgentSteps_keepsObservation_whenNotJson() {
        String stepsJson = """
                [{"thought":"t","action":"search_manual","actionInput":"{}",
                  "observation":"그냥 평문이라 JSON 아님",
                  "referencedChunks":[]}]
                """;
        when(analysisLogService.getLatestAgentStepsJson(1L)).thenReturn(Optional.of(stepsJson));

        List<AgentStepView> steps = assembler.loadAgentSteps(1L);

        assertThat(steps.get(0).observationSummary()).isEqualTo("그냥 평문이라 JSON 아님");
    }

    @Test
    void loadAgentSteps_dedupesReferencedChunksByDocId() {
        String stepsJson = """
                [
                  {"thought":"t","action":"search_manual","actionInput":"{}","observation":"o","referencedChunks":[
                    {"id":10,"manualDocumentId":1,"manualDocumentTitle":"환불 정책","manualCategory":"REFUND","chunkIndex":0,"documentVersion":1,"tokenCount":1,"content":"a"},
                    {"id":11,"manualDocumentId":1,"manualDocumentTitle":"환불 정책","manualCategory":"REFUND","chunkIndex":1,"documentVersion":1,"tokenCount":1,"content":"b"},
                    {"id":12,"manualDocumentId":2,"manualDocumentTitle":"배송 정책","manualCategory":"DELIVERY","chunkIndex":0,"documentVersion":1,"tokenCount":1,"content":"c"}
                  ]}
                ]
                """;
        when(analysisLogService.getLatestAgentStepsJson(1L)).thenReturn(Optional.of(stepsJson));

        List<AgentStepView> steps = assembler.loadAgentSteps(1L);

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).referencedDocs())
                .hasSize(2)
                .extracting(d -> d.docId())
                .containsExactly(1L, 2L);
    }

    @Test
    void loadEvidenceChunks_returnsEmpty_whenCsvAbsent() {
        when(analysisLogService.getLatestRetrievedChunkIds(1L)).thenReturn(Optional.empty());

        assertThat(assembler.loadEvidenceChunks(1L)).isEmpty();
        verifyNoInteractions(manualService);
    }

    @Test
    void loadEvidenceChunks_parsesCsvAndDelegates() {
        when(analysisLogService.getLatestRetrievedChunkIds(1L)).thenReturn(Optional.of("10, 11 , 12"));
        when(manualService.getEvidenceChunks(List.of(10L, 11L, 12L))).thenReturn(List.of());

        assembler.loadEvidenceChunks(1L);

        verify(manualService).getEvidenceChunks(List.of(10L, 11L, 12L));
    }
}
