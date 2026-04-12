package com.aicsassistant.inquiry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
@Entity
@Table(name = "inquiry_message")
public class InquiryMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inquiry_id", nullable = false)
    private Long inquiryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryMessageRole role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected InquiryMessage() {}

    public static InquiryMessage of(Long inquiryId, InquiryMessageRole role, String content) {
        InquiryMessage m = new InquiryMessage();
        m.inquiryId = inquiryId;
        m.role = role;
        m.content = content;
        return m;
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

}
