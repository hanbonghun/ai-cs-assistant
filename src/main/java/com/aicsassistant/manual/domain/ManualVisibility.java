package com.aicsassistant.manual.domain;

/**
 * 정책 문서의 공개 범위.
 *
 * <p>{@code INTERNAL} 은 상담사 지침처럼 고객에게 그대로 나가면 안 되는 문서다. 고객에게 나갈 답을 쓰는 에이전트의
 * 검색 후보에서 코드로 빠진다 — 프롬프트로 "내부 문서는 말하지 마라" 고 하는 대신 모델이 애초에 보지 못하게 한다
 * (ADR 0012). 상담사 화면에는 참고 근거로 따로 붙는다.
 */
public enum ManualVisibility {
    CUSTOMER,
    INTERNAL
}
