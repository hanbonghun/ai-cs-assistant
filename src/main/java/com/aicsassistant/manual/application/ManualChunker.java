package com.aicsassistant.manual.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ManualChunker {

    private final int chunkSize;
    private final int overlapSize;

    public ManualChunker() {
        this(500, 100);
    }

    public ManualChunker(int chunkSize, int overlapSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (overlapSize < 0 || overlapSize >= chunkSize) {
            throw new IllegalArgumentException("overlapSize must be between 0 and chunkSize - 1");
        }
        this.chunkSize = chunkSize;
        this.overlapSize = overlapSize;
    }

    public List<String> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int step = chunkSize - overlapSize;
        int start = 0;

        while (start < content.length()) {
            int end = Math.min(content.length(), start + chunkSize);
            String chunk = content.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end == content.length()) {
                break;
            }
            start += step;
        }

        return chunks;
    }
}
