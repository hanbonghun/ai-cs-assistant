package com.aicsassistant.analysis.dto;

public record RetrievedManualChunkDto(
        Long id,
        Long manualDocumentId,
        String manualDocumentTitle,
        String manualCategory,
        int chunkIndex,
        int documentVersion,
        int tokenCount,
        String content,
        Double similarityScore,
        Double keywordScore
) {
    public RetrievedManualChunkDto(
            Long id,
            Long manualDocumentId,
            String manualDocumentTitle,
            String manualCategory,
            int chunkIndex,
            int documentVersion,
            int tokenCount,
            String content
    ) {
        this(id, manualDocumentId, manualDocumentTitle, manualCategory, chunkIndex, documentVersion, tokenCount, content, null, null);
    }

    public RetrievedManualChunkDto(
            Long id,
            Long manualDocumentId,
            String manualDocumentTitle,
            String manualCategory,
            int chunkIndex,
            int documentVersion,
            int tokenCount,
            String content,
            Double similarityScore
    ) {
        this(id, manualDocumentId, manualDocumentTitle, manualCategory, chunkIndex, documentVersion, tokenCount, content, similarityScore, null);
    }
}
