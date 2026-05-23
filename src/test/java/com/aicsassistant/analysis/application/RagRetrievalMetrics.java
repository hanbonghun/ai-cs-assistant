package com.aicsassistant.analysis.application;

import java.util.List;

final class RagRetrievalMetrics {

    static final int NOT_FOUND_RANK = Integer.MAX_VALUE;

    private RagRetrievalMetrics() {
    }

    static double recallAt(List<Integer> ranks, int k) {
        if (ranks.isEmpty()) {
            return 0.0;
        }

        long hits = ranks.stream()
                .filter(rank -> rank > 0 && rank <= k)
                .count();
        return hits / (double) ranks.size();
    }

    static double meanReciprocalRank(List<Integer> ranks) {
        if (ranks.isEmpty()) {
            return 0.0;
        }

        double total = ranks.stream()
                .mapToDouble(rank -> rank == NOT_FOUND_RANK ? 0.0 : 1.0 / rank)
                .sum();
        return total / ranks.size();
    }

    /**
     * Negative 케이스에서 검색 결과가 비어 있는지(threshold로 컷되는지) 비율.
     */
    static double noMatchAccuracy(List<Integer> retrievedCounts) {
        if (retrievedCounts.isEmpty()) {
            return 0.0;
        }

        long correctlyEmpty = retrievedCounts.stream()
                .filter(count -> count == 0)
                .count();
        return correctlyEmpty / (double) retrievedCounts.size();
    }
}
