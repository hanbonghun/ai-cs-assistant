package com.aicsassistant.analysis.domain;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;

@Getter
@Entity
@Table(name = "inquiry_analysis_log")
public class InquiryAnalysisLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    @Column(name = "request_snapshot", nullable = false, columnDefinition = "text")
    private String requestSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "classified_category", length = 50)
    private InquiryCategory classifiedCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "classified_urgency", length = 20)
    private UrgencyLevel classifiedUrgency;

    @Column(name = "retrieved_chunk_ids", columnDefinition = "text")
    private String retrievedChunkIds;

    @Column(name = "generated_draft", columnDefinition = "text")
    private String generatedDraft;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 20)
    private AnalysisStatus analysisStatus;

    @Column(name = "agent_steps", columnDefinition = "text")
    private String agentSteps;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "ai_draft_rating", length = 10)
    private String aiDraftRating;

    @Column(name = "ai_draft_rating_reason", length = 50)
    private String aiDraftRatingReason;

    @Column(name = "ai_draft_rating_note", columnDefinition = "text")
    private String aiDraftRatingNote;

    public void rate(String rating, String reason, String note) {
        this.aiDraftRating = rating;
        this.aiDraftRatingReason = reason;
        this.aiDraftRatingNote = note;
    }

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected InquiryAnalysisLog() {
    }

    public static InquiryAnalysisLog running(
            Inquiry inquiry,
            String requestSnapshot,
            String modelName,
            String promptVersion
    ) {
        InquiryAnalysisLog log = new InquiryAnalysisLog();
        log.inquiry = inquiry;
        log.requestSnapshot = requestSnapshot;
        log.modelName = modelName;
        log.promptVersion = promptVersion;
        log.analysisStatus = AnalysisStatus.RUNNING;
        return log;
    }

    public void completeSuccess(
            InquiryCategory classifiedCategory,
            UrgencyLevel classifiedUrgency,
            List<Long> retrievedChunkIds,
            String generatedDraft,
            String agentSteps,
            long latencyMs,
            int totalTokens
    ) {
        this.classifiedCategory = classifiedCategory;
        this.classifiedUrgency = classifiedUrgency;
        this.retrievedChunkIds = toChunkIdCsv(retrievedChunkIds);
        this.generatedDraft = generatedDraft;
        this.agentSteps = agentSteps;
        this.analysisStatus = AnalysisStatus.SUCCESS;
        this.errorMessage = null;
        this.latencyMs = latencyMs;
        this.totalTokens = totalTokens;
    }

    public void completeFailure(String errorMessage, long latencyMs) {
        this.analysisStatus = AnalysisStatus.FAILURE;
        this.errorMessage = errorMessage;
        this.latencyMs = latencyMs;
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    private static String toChunkIdCsv(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return "";
        }
        return chunkIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
