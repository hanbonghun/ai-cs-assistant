package com.aicsassistant.manual.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
@Entity
@Table(name = "manual_chunk")
public class ManualChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "manual_document_id", nullable = false)
    private ManualDocument manualDocument;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "document_version", nullable = false)
    private Integer documentVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "token_count", nullable = false)
    private Integer tokenCount;

    @Column(columnDefinition = "vector(1536)")
    private String embedding;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ManualChunk() {
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

}
