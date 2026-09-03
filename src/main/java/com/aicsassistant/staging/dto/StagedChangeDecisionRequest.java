package com.aicsassistant.staging.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 승인·거부 요청.
 *
 * <p>{@code decisionNote}는 거부에서 필수(도메인에서 검증), 승인에서 선택이다.
 * {@code approvedAmount}는 승인에서만 의미가 있고, 생략하면 제안 금액을 그대로 승인한 것이다.
 */
public record StagedChangeDecisionRequest(
        @NotBlank String decidedBy,
        String decisionNote,
        Integer approvedAmount
) {}
