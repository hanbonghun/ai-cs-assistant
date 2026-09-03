package com.aicsassistant.staging.dto;

import com.aicsassistant.staging.domain.StagedChange;
import java.time.LocalDateTime;

public record StagedChangeResponse(
        Long id,
        String changeType,
        String orderId,
        int amount,
        Integer approvedAmount,
        String reason,
        String policyBasis,
        String status,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionNote,
        LocalDateTime createdAt
) {
    public static StagedChangeResponse from(StagedChange change) {
        return new StagedChangeResponse(
                change.getId(),
                change.getChangeType().name(),
                change.getOrderId(),
                change.getAmount(),
                change.getApprovedAmount(),
                change.getReason(),
                change.getPolicyBasis(),
                change.getStatus().name(),
                change.getDecidedBy(),
                change.getDecidedAt(),
                change.getDecisionNote(),
                change.getCreatedAt());
    }
}
