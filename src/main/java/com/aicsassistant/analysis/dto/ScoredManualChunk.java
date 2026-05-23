package com.aicsassistant.analysis.dto;

public record ScoredManualChunk(RetrievedManualChunkDto chunk, double score) {
}
