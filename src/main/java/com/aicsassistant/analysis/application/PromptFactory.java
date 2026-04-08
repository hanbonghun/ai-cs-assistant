package com.aicsassistant.analysis.application;

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

    private static final String PROMPT_VERSION = "v1";

    public String promptVersion() {
        return PROMPT_VERSION;
    }

    public String buildClassificationPrompt(String inquiryContent) {
        String categories = Arrays.stream(InquiryCategory.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        String urgencyLevels = Arrays.stream(UrgencyLevel.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));

        return """
                You are a customer inquiry analyzer.
                Classify the inquiry and produce only JSON.

                Allowed category values: %s
                Allowed urgency values: %s

                JSON schema:
                {
                  "category": "<one of allowed categories>",
                  "urgency": "<one of allowed urgency values>",
                  "reason": "<concise explanation>",
                  "needsHumanReview": true|false,
                  "needsEscalation": true|false,
                  "medicalRiskFlag": true|false
                }

                Inquiry:
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
                You are drafting a customer support response in Korean.
                Use only the provided manual chunks as policy basis.
                Keep customer-facing language polite and concise.
                Return only JSON.

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
