package com.aicsassistant.inquiry.domain;

import com.aicsassistant.common.exception.ApiException;
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
import org.springframework.http.HttpStatus;

@Getter
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
    @Column(length = 50)
    private InquiryCategory category;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
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

    @Column(name = "related_order_id", length = 50)
    private String relatedOrderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Inquiry() {
    }

    public static Inquiry create(String customerIdentifier, String title, String content) {
        return create(customerIdentifier, title, content, null, null);
    }

    public static Inquiry create(
            String customerIdentifier,
            String title,
            String content,
            InquiryCategory category,
            UrgencyLevel urgency
    ) {
        return create(customerIdentifier, title, content, category, urgency, null);
    }

    public static Inquiry create(
            String customerIdentifier,
            String title,
            String content,
            InquiryCategory category,
            UrgencyLevel urgency,
            String relatedOrderId
    ) {
        Inquiry inquiry = new Inquiry();
        inquiry.customerIdentifier = customerIdentifier;
        inquiry.title = title;
        inquiry.content = content;
        inquiry.category = category;
        inquiry.urgency = urgency;
        inquiry.relatedOrderId = relatedOrderId;
        inquiry.status = InquiryStatus.NEW;
        return inquiry;
    }

    public void markAiProcessed() {
        if (status != InquiryStatus.NEW) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE", "Only NEW inquiries can be AI processed");
        }
        status = InquiryStatus.AI_PROCESSED;
    }

    public void askFollowUp() {
        if (status != InquiryStatus.NEW && status != InquiryStatus.PENDING_CUSTOMER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE",
                    "Only NEW or PENDING_CUSTOMER inquiries can ask a follow-up");
        }
        this.status = InquiryStatus.PENDING_CUSTOMER;
    }

    public void applyAnalysis(InquiryCategory category, UrgencyLevel urgency, String aiDraftAnswer) {
        if (status == InquiryStatus.CLOSED || status == InquiryStatus.REVIEWED
                || status == InquiryStatus.AUTO_ANSWERED) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_INQUIRY_STATE",
                    "Only NEW, PENDING_CUSTOMER, or AI_PROCESSED inquiries can be analyzed"
            );
        }
        this.category = category;
        this.urgency = urgency;
        this.aiDraftAnswer = aiDraftAnswer;
        this.status = InquiryStatus.AI_PROCESSED;
    }

    public void autoProcess(String reviewedBy) {
        if (status != InquiryStatus.AI_PROCESSED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE",
                    "Only AI_PROCESSED inquiries can be auto-processed");
        }
        this.finalAnswer = this.aiDraftAnswer;
        this.reviewedBy = reviewedBy;
        this.reviewMemo = "AI 자동 처리";
        this.status = InquiryStatus.AUTO_ANSWERED;
    }

    public void confirmReview(String finalAnswer, String reviewMemo, String reviewedBy) {
        if (status != InquiryStatus.NEW && status != InquiryStatus.AI_PROCESSED) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_INQUIRY_STATE",
                    "Only NEW or AI_PROCESSED inquiries can be reviewed"
            );
        }
        this.finalAnswer = finalAnswer;
        this.reviewMemo = reviewMemo;
        this.reviewedBy = reviewedBy;
        status = InquiryStatus.REVIEWED;
    }

    public void close() {
        if (status != InquiryStatus.REVIEWED && status != InquiryStatus.AUTO_ANSWERED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE",
                    "Only REVIEWED or AUTO_ANSWERED inquiries can be closed");
        }
        status = InquiryStatus.CLOSED;
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
