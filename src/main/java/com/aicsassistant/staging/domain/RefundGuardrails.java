package com.aicsassistant.staging.domain;

import java.util.Set;

/** 환불 제안·승인의 공통 판정 기준. 제안 시점과 승인 시점은 반드시 같은 목록을 본다. */
public final class RefundGuardrails {

    /**
     * 이미 환불이 끝났거나 진행 중인 주문 상태 — 환불을 제안·승인할 수 없다. {@code 환불완료}
     * (승인 시점에 {@code markRefunded} 가 만드는 상태) 를 포함하는 이유: 제안 시점에 이 상태를
     * 몰랐다면, 승인이 이미 끝난 주문에 대해 새 제안이 접수되고 상담사가 승인을 누를 때만
     * ALREADY_REFUNDED_STATUS 로 뒤늦게 막히는 카드가 생긴다.
     *
     * <p>ponytail: 부분환불완료는 남은 금액 환불이 정당할 수 있어 제외한다. 대신 금액 상한이
     * 결제금액 전액이라 이미 환불된 몫을 차감하지 못한다 — mock 데이터가 부분환불 금액을
     * {@code note} 텍스트에만 갖고 있기 때문이다. 남은 판단은 승인 화면의 상담사에게 있다.
     * 주문 도메인이 환불 이력을 구조적으로 제공하면 상한을 (결제금액 - 기환불액)으로 좁힌다.
     * 같은 이유로, 부분 금액만 승인해도 주문 상태는 (부분환불완료가 아니라) 환불완료로 바뀌므로
     * 남은 금액에 대한 정당한 후속 환불도 승인 표면에서 영구히 막힌다 — mock 데이터 문제가
     * 아니라 코드 로직의 한계다.
     */
    public static final Set<String> REFUND_BLOCKING_STATUSES = Set.of("취소완료", "취소처리중", "반품완료", "환불완료");

    /** 환불이 이미 실행된 주문 상태. 승인 시점 재검사에서 쓴다. */
    public static final String ALREADY_REFUNDED_STATUS = "환불완료";

    private RefundGuardrails() {
    }
}
