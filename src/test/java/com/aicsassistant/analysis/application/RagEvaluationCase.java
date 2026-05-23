package com.aicsassistant.analysis.application;

import java.util.List;

record RagEvaluationCase(
        String query,
        String category,
        Difficulty difficulty,
        String expectedManualTitle,
        List<String> expectedChunkKeywords,
        String answerMustInclude,
        boolean negative
) {

    enum Difficulty {
        EASY, MEDIUM, HARD;

        static Difficulty parse(String raw) {
            return Difficulty.valueOf(raw.trim().toUpperCase());
        }
    }
}
