package com.aicsassistant.staging.domain;

import com.aicsassistant.common.exception.ApiException;
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
import org.springframework.http.HttpStatus;

/**
 * 에이전트가 제안했고 아직 실행되지 않은 상태 변경.
 *
 * <p>실행 권한은 이 레코드에 없다. 상담사가 승인 표면에서 승인해야
 * {@code StagedChangeApprovalService}가 실행한다. 결정 이력(누가·언제·왜)이
 * 이 레코드에 남으므로 별도 감사 테이블을 두지 않는다.
 */
@Getter
@Entity
@Table(name = "staged_change")
public class StagedChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inquiry_id", nullable = false)
    private Long inquiryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    @Column(name = "order_id", nullable = false, length = 50)
    private String orderId;

    @Column(nullable = false)
    private int amount;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "policy_basis", columnDefinition = "text")
    private String policyBasis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StagedChangeStatus status;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;

    @Column(name = "approved_amount")
    private Integer approvedAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected StagedChange() {
    }

    public static StagedChange propose(
            Long inquiryId, ChangeType changeType, String orderId,
            int amount, String reason, String policyBasis) {
        StagedChange change = new StagedChange();
        change.inquiryId = inquiryId;
        change.changeType = changeType;
        change.orderId = orderId;
        change.amount = amount;
        change.reason = reason;
        change.policyBasis = policyBasis;
        change.status = StagedChangeStatus.PENDING;
        return change;
    }

    /**
     * 승인한다. {@code approvedAmount} 가 null 이면 제안 금액을 그대로 승인한 것이다.
     *
     * <p>제안 금액은 덮어쓰지 않는다 — AI 가 얼마를 제안했고 사람이 얼마로 고쳤는지가 이력으로 남아야 한다.
     */
    public void approve(String decidedBy, String decisionNote, Integer approvedAmount) {
        requirePending();
        if (approvedAmount != null && approvedAmount <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_APPROVED_AMOUNT",
                    "승인 금액은 0보다 커야 합니다.");
        }
        this.status = StagedChangeStatus.APPROVED;
        this.decidedBy = decidedBy;
        this.decisionNote = decisionNote;
        this.approvedAmount = approvedAmount;
        this.decidedAt = LocalDateTime.now();
    }

    /** 실행·알림·재검사의 기준이 되는 최종 금액. */
    public int effectiveAmount() {
        return approvedAmount != null ? approvedAmount : amount;
    }

    public void reject(String decidedBy, String decisionNote) {
        requirePending();
        if (decisionNote == null || decisionNote.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DECISION_NOTE_REQUIRED",
                    "거부 사유는 필수입니다.");
        }
        this.status = StagedChangeStatus.REJECTED;
        this.decidedBy = decidedBy;
        this.decisionNote = decisionNote;
        this.decidedAt = LocalDateTime.now();
    }

    private void requirePending() {
        if (status != StagedChangeStatus.PENDING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALREADY_DECIDED",
                    "이미 " + status + " 상태인 제안입니다. (ALREADY_DECIDED)");
        }
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
