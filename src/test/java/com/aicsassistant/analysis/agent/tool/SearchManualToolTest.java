package com.aicsassistant.analysis.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.analysis.application.ManualRetrievalService;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchManualToolTest {

    @Mock ManualRetrievalService manualRetrievalService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsValidationErrorWhenQueryMissing() {
        SearchManualTool tool = new SearchManualTool(manualRetrievalService);

        ToolResult result = tool.execute(input(""));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.errorMessage()).contains("query");
    }

    @Test
    void returnsSuccessWhenChunksFound() {
        SearchManualTool tool = new SearchManualTool(manualRetrievalService);
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of(
                new RetrievedManualChunkDto(1L, 10L, "환불 정책", "REFUND", 0, 1, 50, "환불 가능 기간은 7일입니다.")
        ));

        ToolResult result = tool.execute(input("환불 정책"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).contains("환불 정책").contains("환불 가능 기간은 7일입니다.");
    }

    @Test
    void returnsSuccessWithEmptyMarkerWhenNoChunks() {
        SearchManualTool tool = new SearchManualTool(manualRetrievalService);
        when(manualRetrievalService.retrieve(any())).thenReturn(List.of());

        ToolResult result = tool.execute(input("존재하지 않는 정책"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).contains("No relevant policy documents");
    }

    private ObjectNode input(String query) {
        return objectMapper.createObjectNode().put("query", query);
    }
}
