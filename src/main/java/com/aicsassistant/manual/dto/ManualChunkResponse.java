package com.aicsassistant.manual.dto;

import java.time.LocalDateTime;

public record ManualChunkResponse(
        Long id,
        Long manualDocumentId,
        Integer chunkIndex,
        Integer documentVersion,
        String content,
        Integer tokenCount,
        boolean active,
        LocalDateTime createdAt
) {
}
