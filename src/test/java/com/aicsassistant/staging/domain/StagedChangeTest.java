package com.aicsassistant.staging.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.common.exception.ApiException;
import org.junit.jupiter.api.Test;

class StagedChangeTest {

    private StagedChange pendingRefund() {
        return StagedChange.propose(1L, ChangeType.REFUND, "ORD-20260405-002", 45_000,
                "배송완료 4일 경과, 반품 가능 기간 이내", "반품 정책 3조");
    }

    @Test
    void proposeStartsAsPending() {
        StagedChange change = pendingRefund();

        assertThat(change.getStatus()).isEqualTo(StagedChangeStatus.PENDING);
        assertThat(change.getAmount()).isEqualTo(45_000);
        assertThat(change.getDecidedBy()).isNull();
        assertThat(change.getDecidedAt()).isNull();
    }

    @Test
    void approveRecordsDeciderAndTimestamp() {
        StagedChange change = pendingRefund();

        change.approve("counselor-demo", null);

        assertThat(change.getStatus()).isEqualTo(StagedChangeStatus.APPROVED);
        assertThat(change.getDecidedBy()).isEqualTo("counselor-demo");
        assertThat(change.getDecidedAt()).isNotNull();
    }

    @Test
    void rejectRequiresNote() {
        StagedChange change = pendingRefund();

        assertThatThrownBy(() -> change.reject("counselor-demo", "  "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("거부 사유");
    }

    @Test
    void rejectRecordsNote() {
        StagedChange change = pendingRefund();

        change.reject("counselor-demo", "고객 주장과 배송 기록이 불일치");

        assertThat(change.getStatus()).isEqualTo(StagedChangeStatus.REJECTED);
        assertThat(change.getDecisionNote()).isEqualTo("고객 주장과 배송 기록이 불일치");
    }

    @Test
    void cannotDecideTwice() {
        StagedChange change = pendingRefund();
        change.approve("counselor-demo", null);

        assertThatThrownBy(() -> change.approve("other", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ALREADY_DECIDED");
        assertThatThrownBy(() -> change.reject("other", "사유"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ALREADY_DECIDED");
    }
}
