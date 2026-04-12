package com.aicsassistant.manual.domain;

import com.aicsassistant.inquiry.domain.InquiryCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
@Entity
@Table(name = "manual_document")
public class ManualDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private InquiryCategory category;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ManualDocument() {
    }

    public static ManualDocument create(String title, InquiryCategory category, String content) {
        ManualDocument document = new ManualDocument();
        document.title = title;
        document.category = category;
        document.content = content;
        document.version = 1;
        document.active = true;
        return document;
    }

    public void update(String title, InquiryCategory category, String content) {
        this.title = title;
        this.category = category;
        this.content = content;
        this.version = version + 1;
    }

    public void deactivate() {
        this.active = false;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

}
