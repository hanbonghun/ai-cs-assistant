package com.aicsassistant.analysis.application;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import java.util.List;

/**
 * 한 번의 분석 실행이 트랜잭션 경계를 넘어 나르는 값.
 *
 * <p>{@link InquiryAnalysisRecorder#startAnalysis} 가 읽기 트랜잭션 안에서 만들고, 에이전트 실행
 * (트랜잭션 없음)을 지나 결과 기록 트랜잭션까지 전달된다. 그래서 여기 담긴 {@code inquiry} 는
 * <b>detached</b> 다. {@code Inquiry} 에 지연 로딩 연관이 하나도 없어(전부 {@code @Column}/
 * {@code @Enumerated}) 읽기만 하는 에이전트 실행 구간에서는 안전하다.
 *
 * <p>저장 단계는 이 엔티티를 쓰지 않고 재조회한다 — 에이전트가 도는 십수 초 사이에 상담사가
 * 문의를 종료했을 수 있기 때문이다. 근거는 ADR 0009.
 */
record AnalysisContext(
        Inquiry inquiry,
        List<InquiryMessage> history,
        Long logId,
        long startedAtMillis
) {
}
