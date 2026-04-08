package com.aicsassistant.manual.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ManualChunkerTest {

    @Test
    void splitsManualIntoStableChunks() {
        ManualChunker chunker = new ManualChunker(500, 100);

        List<String> chunks = chunker.chunk("A".repeat(1200));

        assertThat(chunks).hasSizeGreaterThan(1);
    }
}
