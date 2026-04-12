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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected InquiryAnalysisLog() {
    }

    public static InquiryAnalysisLog success(
            Inquiry inquiry,
            String requestSnapshot,
            InquiryCategory classifiedCategory,
            UrgencyLevel classifiedUrgency,
            List<Long> retrievedChunkIds,
            String generatedDraft,
            String modelName,
            String promptVersion,
            String agentSteps,
            long latencyMs,
            int totalTokens
    ) {
        InquiryAnalysisLog log = new InquiryAnalysisLog();
        log.inquiry = inquiry;
        log.requestSnapshot = requestSnapshot;
        log.classifiedCategory = classifiedCategory;
        log.classifiedUrgency = classifiedUrgency;
        log.retrievedChunkIds = toChunkIdCsv(retrievedChunkIds);
        log.generatedDraft = generatedDraft;
        log.modelName = modelName;
        log.promptVersion = promptVersion;
        log.agentSteps = agentSteps;
        log.analysisStatus = AnalysisStatus.SUCCESS;
        log.errorMessage = null;
        log.latencyMs = latencyMs;
        log.totalTokens = totalTokens;
        return log;
    }

    public static InquiryAnalysisLog failure(
            Inquiry inquiry,
            String requestSnapshot,
            String modelName,
            String promptVersion,
            String errorMessage,
            long latencyMs
    ) {
        InquiryAnalysisLog log = new InquiryAnalysisLog();
        log.inquiry = inquiry;
        log.requestSnapshot = requestSnapshot;
        log.classifiedCategory = null;
        log.classifiedUrgency = null;
        log.retrievedChunkIds = null;
        log.generatedDraft = null;
        log.modelName = modelName;
        log.promptVersion = promptVersion;
        log.analysisStatus = AnalysisStatus.FAILURE;
        log.errorMessage = errorMessage;
        log.latencyMs = latencyMs;
        return log;
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
