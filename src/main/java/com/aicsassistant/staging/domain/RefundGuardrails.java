package com.aicsassistant.staging.domain;

import java.util.Set;

/** 환불 제안·승인의 공통 판정 기준. 제안 시점과 승인 시점이 같은 목록을 봐야 한다. */
public final class RefundGuardrails {

    /**
     * 이미 환불이 끝났거나 진행 중인 주문 상태 — 환불을 제안·승인할 수 없다.
     *
     * <p>ponytail: 부분환불완료는 남은 금액 환불이 정당할 수 있어 제외한다. 대신 금액 상한이
     * 결제금액 전액이라 이미 환불된 몫을 차감하지 못한다 — mock 데이터가 부분환불 금액을
     * {@code note} 텍스트에만 갖고 있기 때문이다. 남은 판단은 승인 화면의 상담사에게 있다.
     * 주문 도메인이 환불 이력을 구조적으로 제공하면 상한을 (결제금액 - 기환불액)으로 좁힌다.
     */
    public static final Set<String> REFUND_BLOCKING_STATUSES = Set.of("취소완료", "취소처리중", "반품완료");

    /** 환불이 이미 실행된 주문 상태. 승인 시점 재검사에서 쓴다. */
    public static final String ALREADY_REFUNDED_STATUS = "환불완료";

    private RefundGuardrails() {
    }
}
