package com.aicsassistant.inquiry.domain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public enum UrgencyLevel {
    LOW("낮음"),
    MEDIUM("보통"),
    HIGH("높음");

    private final String label;

    UrgencyLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static Map<String, String> labels() {
        return Arrays.stream(values())
                .collect(Collectors.toMap(Enum::name, UrgencyLevel::getLabel, (a, b) -> a, LinkedHashMap::new));
    }
}
