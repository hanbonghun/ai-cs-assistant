package com.aicsassistant.analysis.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.analysis.application.ManualRetrievalService;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchManualToolTest {

    @Mock ManualRetrievalService manualRetrievalService;

    @Test
    void returnsValidationErrorWhenQueryMissing() {
        SearchManualTool tool = new SearchManualTool(manualRetrievalService);

        ToolResult result = tool.execute(new SearchManualTool.Input(""));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.errorMessage()).contains("query");
    }

    @Test
    void returnsValidationErrorWhenQueryNull() {
        SearchManualTool tool = new SearchManualTool(manualRetrievalService);

        ToolResult result = tool.execute(new SearchManualTool.Input(null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
    }

    @Test
    void returnsSuccessWhenChunksFound() {
        SearchManualTool tool = new SearchManualTool(manualRetrievalService);
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of(
                new RetrievedManualChunkDto(1L, 10L, "환불 정책", "REFUND", 0, 1, 50, "환불 가능 기간은 7일입니다.")
        ));

        ToolResult result = tool.execute(new SearchManualTool.Input("환불 정책"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).contains("환불 정책").contains("환불 가능 기간은 7일입니다.");
    }

    @Test
    void returnsSuccessWithEmptyMarkerWhenNoChunks() {
        SearchManualTool tool = new SearchManualTool(manualRetrievalService);
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of());

        ToolResult result = tool.execute(new SearchManualTool.Input("존재하지 않는 정책"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).contains("No relevant policy documents");
    }

    @Test
    void exposesAllSurfaceFieldsForLlm() {
        SearchManualTool tool = new SearchManualTool(manualRetrievalService);

        assertThat(tool.name()).isEqualTo("search_manual");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.whenToUse()).isNotBlank();
        assertThat(tool.inputType()).isEqualTo(SearchManualTool.Input.class);
        assertThat(tool.inputSchema()).contains("query");
        assertThat(tool.successOutputHint()).isNotBlank();
        assertThat(tool.failureBehavior()).contains("VALIDATION");
    }

    @Test
    void usageBoundaryRedirectsToSiblingTool() {
        // 가이드 4번: 유사 도구와의 경계가 명시되어 있어야 모델이 잘못 고르지 않는다
        SearchManualTool tool = new SearchManualTool(manualRetrievalService);

        assertThat(tool.usageBoundary())
                .contains("Do NOT use")
                .contains("check_order_status");
    }
}
