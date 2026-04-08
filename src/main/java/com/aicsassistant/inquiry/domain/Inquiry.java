package com.aicsassistant.inquiry.domain;

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

@Entity
@Table(name = "inquiry")
public class Inquiry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_identifier", nullable = false, length = 100)
    private String customerIdentifier;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private InquiryCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UrgencyLevel urgency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryStatus status;

    @Column(name = "ai_draft_answer", columnDefinition = "text")
    private String aiDraftAnswer;

    @Column(name = "final_answer", columnDefinition = "text")
    private String finalAnswer;

    @Column(name = "review_memo", columnDefinition = "text")
    private String reviewMemo;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Inquiry() {
    }

    public static Inquiry create(String customerIdentifier, String title, String content) {
        Inquiry inquiry = new Inquiry();
        inquiry.customerIdentifier = customerIdentifier;
        inquiry.title = title;
        inquiry.content = content;
        inquiry.category = InquiryCategory.GENERAL;
        inquiry.urgency = UrgencyLevel.MEDIUM;
        inquiry.status = InquiryStatus.NEW;
        return inquiry;
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

    public Long getId() {
        return id;
    }

    public String getCustomerIdentifier() {
        return customerIdentifier;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public InquiryCategory getCategory() {
        return category;
    }

    public UrgencyLevel getUrgency() {
        return urgency;
    }

    public InquiryStatus getStatus() {
        return status;
    }

    public String getAiDraftAnswer() {
        return aiDraftAnswer;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public String getReviewMemo() {
        return reviewMemo;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
