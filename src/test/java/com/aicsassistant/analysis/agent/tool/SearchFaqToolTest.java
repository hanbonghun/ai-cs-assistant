package com.aicsassistant.analysis.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.faq.InMemoryFaqRepository;
import org.junit.jupiter.api.Test;

class SearchFaqToolTest {

    private final SearchFaqTool tool = new SearchFaqTool(new InMemoryFaqRepository());

    @Test
    void returnsSuccessForKnownFaqKeyword() {
        ToolResult result = tool.execute(new SearchFaqTool.Input("환불 며칠 걸려요?"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data())
                .startsWith("Q: ")
                .contains("환불")
                .contains("A: ");
    }

    @Test
    void returnsValidationErrorWhenQuestionMissing() {
        ToolResult result = tool.execute(new SearchFaqTool.Input(""));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void returnsValidationErrorWhenQuestionNull() {
        ToolResult result = tool.execute(new SearchFaqTool.Input(null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
    }

    @Test
    void returnsNotFoundForUnknownQuestionAndRedirectsToSiblingTool() {
        ToolResult result = tool.execute(new SearchFaqTool.Input("우주선 발사 절차 알려주세요"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.NOT_FOUND);
        assertThat(result.isRetryable()).isFalse();
        // 가이드 4번: NOT_FOUND 시 형제 도구로 폴백 안내가 있어야 함
        assertThat(result.errorMessage()).contains("search_manual");
    }

    @Test
    void exposesAllSurfaceFieldsForLlm() {
        assertThat(tool.name()).isEqualTo("search_faq");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.whenToUse()).isNotBlank();
        assertThat(tool.inputType()).isEqualTo(SearchFaqTool.Input.class);
        assertThat(tool.inputSchema()).contains("question");
        assertThat(tool.successOutputHint()).isNotBlank();
        assertThat(tool.failureBehavior()).contains("NOT_FOUND");
    }

    @Test
    void usageBoundaryDifferentiatesFromSimilarTool() {
        // 가이드: 유사 기능 도구가 있을 때 boundary가 명확해야 모델이 잘못 고르지 않음
        assertThat(tool.usageBoundary())
                .contains("Do NOT use")
                .contains("search_manual")  // 더 자세한 정책은 search_manual로
                .contains("check_order_status");  // 주문 데이터는 check_order_status로
    }
}
