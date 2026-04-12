package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptFactoryTest {

    private final PromptFactory promptFactory = new PromptFactory();

    @Test
    void buildsClassificationPromptWithOutputSchema() {
        String prompt = promptFactory.buildClassificationPrompt("환불을 요청합니다.");

        assertThat(prompt).contains("category");
        assertThat(prompt).contains("urgency");
        assertThat(prompt).contains("needsHumanReview");
        assertThat(prompt).contains("fraudRiskFlag");
        assertThat(prompt).contains("환불을 요청합니다.");
    }

    @Test
    void buildsDraftPromptWithRetrievedChunks() {
        CategoryResultDto category = new CategoryResultDto("REFUND", "refund request", true, false, false);
        UrgencyResultDto urgency = new UrgencyResultDto("MEDIUM", "standard SLA");
        List<RetrievedManualChunkDto> chunks = List.of(
                new RetrievedManualChunkDto(1L, 1L, "환불 안내", "REFUND", 0, 1, 12, "환불은 영업일 기준 3일 내 처리됩니다.")
        );

        String prompt = promptFactory.buildDraftPrompt("환불 문의", category, urgency, chunks);

        assertThat(prompt).contains("REFUND");
        assertThat(prompt).contains("MEDIUM");
        assertThat(prompt).contains("환불은 영업일 기준 3일 내 처리됩니다.");
        assertThat(prompt).contains("\"usedChunkIds\"");
    }
}
