package com.aicsassistant.inquiry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InquiryStatusTest {

    /**
     * 상태를 새로 추가하면 이 테스트가 깨진다 — 그게 목적이다.
     * "이 상태에 분석 결과를 반영해도 되는가" 를 결정하지 않고 지나가지 못하게 한다.
     */
    @Test
    @DisplayName("종료 상태는 답변이 확정된 세 가지뿐이다")
    void finishedStatusesArePinned() {
        assertThat(InquiryStatus.values())
                .filteredOn(InquiryStatus::isFinished)
                .containsExactlyInAnyOrder(
                        InquiryStatus.AUTO_ANSWERED,
                        InquiryStatus.REVIEWED,
                        InquiryStatus.CLOSED);
    }
}
