package com.aicsassistant.analysis.application;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class RagGoldenSetLoader {

    private static final int EXPECTED_COLUMNS = 7;
    private static final String MULTI_VALUE_DELIMITER = "\\|";

    private RagGoldenSetLoader() {
    }

    static List<RagEvaluationCase> load(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(RagGoldenSetLoader::parseLine)
                    .toList();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("Failed to load RAG golden set: " + path, e);
        }
    }

    private static RagEvaluationCase parseLine(String line) {
        String[] columns = splitCsv(line);
        if (columns.length != EXPECTED_COLUMNS) {
            throw new IllegalArgumentException(
                    "RAG golden set line must have " + EXPECTED_COLUMNS + " columns but was " + columns.length + ": " + line);
        }

        boolean negative = Boolean.parseBoolean(columns[6].trim());
        String expectedTitle = columns[3].trim();
        String answerMustInclude = columns[5].trim();

        return new RagEvaluationCase(
                columns[0].trim(),
                columns[1].trim(),
                RagEvaluationCase.Difficulty.parse(columns[2]),
                expectedTitle.isEmpty() ? null : expectedTitle,
                parseMultiValue(columns[4]),
                answerMustInclude.isEmpty() ? null : answerMustInclude,
                negative
        );
    }

    private static List<String> parseMultiValue(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(trimmed.split(MULTI_VALUE_DELIMITER))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static String[] splitCsv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        result.add(current.toString());
        return result.toArray(new String[0]);
    }
}
