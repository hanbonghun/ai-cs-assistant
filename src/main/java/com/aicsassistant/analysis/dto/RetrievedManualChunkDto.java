package com.aicsassistant.analysis.dto;

public record RetrievedManualChunkDto(
        Long id,
        Long manualDocumentId,
        String manualDocumentTitle,
        String manualCategory,
        int chunkIndex,
        int documentVersion,
        int tokenCount,
        String content
) {
}
