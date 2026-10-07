package com.aicsassistant.manual.dto;

/** 분석 근거로 쓰인 매뉴얼 청크 — 문서 제목·카테고리를 함께 담는다. */
public record EvidenceChunkResponse(
        Long id,
        Long manualDocumentId,
        String manualDocumentTitle,
        String manualCategory,
        Integer chunkIndex,
        Integer documentVersion,
        Integer tokenCount,
        String content
) {
}
