package com.aicsassistant.ui.application;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbcTemplate;

    public DashboardStats stats() {
        Long totalInquiries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiry", Long.class);

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT status, COUNT(*) AS cnt FROM inquiry GROUP BY status ORDER BY cnt DESC",
                (RowCallbackHandler) rs -> statusCounts.put(rs.getString("status"), rs.getLong("cnt")));

        Long autoAnswered = statusCounts.getOrDefault("AUTO_ANSWERED", 0L);
        long total = totalInquiries != null ? totalInquiries : 0L;
        double autoAnswerRate = total > 0 ? (autoAnswered * 100.0 / total) : 0.0;

        Long totalAnalyzed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiry_analysis_log WHERE analysis_status = 'SUCCESS'", Long.class);

        Double avgLatencyMs = jdbcTemplate.queryForObject(
                "SELECT AVG(latency_ms) FROM inquiry_analysis_log WHERE analysis_status = 'SUCCESS' AND latency_ms IS NOT NULL",
                Double.class);

        Double avgTokens = jdbcTemplate.queryForObject(
                "SELECT AVG(total_tokens) FROM inquiry_analysis_log WHERE total_tokens IS NOT NULL",
                Double.class);

        Map<String, Long> ratingCounts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT ai_draft_rating, COUNT(*) AS cnt FROM inquiry_analysis_log WHERE ai_draft_rating IS NOT NULL GROUP BY ai_draft_rating",
                (RowCallbackHandler) rs -> ratingCounts.put(rs.getString("ai_draft_rating"), rs.getLong("cnt")));

        Map<String, Long> ratingReasonCounts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT ai_draft_rating_reason, COUNT(*) AS cnt FROM inquiry_analysis_log WHERE ai_draft_rating_reason IS NOT NULL GROUP BY ai_draft_rating_reason ORDER BY cnt DESC",
                (RowCallbackHandler) rs -> ratingReasonCounts.put(rs.getString("ai_draft_rating_reason"), rs.getLong("cnt")));

        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT classified_category, COUNT(*) AS cnt FROM inquiry_analysis_log WHERE classified_category IS NOT NULL GROUP BY classified_category ORDER BY cnt DESC",
                (RowCallbackHandler) rs -> categoryCounts.put(rs.getString("classified_category"), rs.getLong("cnt")));

        return new DashboardStats(
                total,
                totalAnalyzed != null ? totalAnalyzed : 0L,
                String.format("%.1f", autoAnswerRate),
                avgLatencyMs != null ? String.format("%.1f", avgLatencyMs / 1000.0) : "-",
                avgTokens != null ? String.format("%.0f", avgTokens) : "-",
                statusCounts,
                ratingCounts,
                ratingReasonCounts,
                categoryCounts,
                categoryCounts.values().stream().mapToLong(Long::longValue).sum(),
                ratingCounts.values().stream().mapToLong(Long::longValue).sum(),
                ratingReasonCounts.values().stream().mapToLong(Long::longValue).sum()
        );
    }

    public record DashboardStats(
            long totalInquiries,
            long totalAnalyzed,
            String autoAnswerRate,
            String avgLatencySec,
            String avgTokens,
            Map<String, Long> statusCounts,
            Map<String, Long> ratingCounts,
            Map<String, Long> ratingReasonCounts,
            Map<String, Long> categoryCounts,
            long catTotal,
            long ratingTotal,
            long reasonTotal
    ) {}
}
