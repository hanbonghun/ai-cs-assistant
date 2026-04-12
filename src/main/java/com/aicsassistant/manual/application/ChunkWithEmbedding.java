package com.aicsassistant.manual.application;

import java.util.List;

public record ChunkWithEmbedding(String content, List<Double> embedding) {

    public boolean hasEmbedding() {
        return embedding != null && !embedding.isEmpty();
    }

    public String toVectorLiteral() {
        if (!hasEmbedding()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}
