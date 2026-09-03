package com.aicsassistant.staging.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.staging.domain.ChangeType;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.domain.StagedChangeStatus;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class StagedChangeRepositoryTest extends PostgresVectorIntegrationTest {

    @Autowired
    StagedChangeRepository stagedChangeRepository;

    @Autowired
    InquiryRepository inquiryRepository;

    private Long newInquiryId() {
        return inquiryRepository.save(Inquiry.create("cust-001", "문의", "환불 요청")).getId();
    }

    @Test
    void savesAndReadsBackProposal() {
        Long inquiryId = newInquiryId();

        StagedChange saved = stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, "ORD-20260405-002", 45_000, "기간 이내", "반품 정책 3조"));

        StagedChange found = stagedChangeRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(StagedChangeStatus.PENDING);
        assertThat(found.getOrderId()).isEqualTo("ORD-20260405-002");
        assertThat(found.getAmount()).isEqualTo(45_000);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void existsByOrderIdAndStatusFindsOnlyPending() {
        Long inquiryId = newInquiryId();
        StagedChange change = stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, "ORD-20260405-002", 45_000, "기간 이내", null));

        assertThat(stagedChangeRepository
                .existsByOrderIdAndStatus("ORD-20260405-002", StagedChangeStatus.PENDING)).isTrue();

        change.reject("counselor-demo", "기록 불일치");
        stagedChangeRepository.save(change);

        assertThat(stagedChangeRepository
                .existsByOrderIdAndStatus("ORD-20260405-002", StagedChangeStatus.PENDING)).isFalse();
    }

    @Test
    void findsByInquiryNewestFirst() {
        Long inquiryId = newInquiryId();
        stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, "ORD-A", 10_000, "첫 제안", null));
        stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, "ORD-B", 20_000, "두 번째 제안", null));

        List<StagedChange> found = stagedChangeRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(StagedChange::getOrderId).containsExactlyInAnyOrder("ORD-A", "ORD-B");
    }

    @Test
    void secondApprovalOnSameOrderViolatesUniqueIndexImmediatelyAtFlush() {
        // uq_staged_change_order_approved (schema.sql) 가 실제로 걸려 있고, 커밋까지 기다리지 않고
        // saveAndFlush 호출 자체 안에서 즉시 위반이 드러나는지 검증한다 — 별도 트랜잭션 커밋이나
        // 스레드 없이도 이 메서드 호출 시점에 예외가 나오면 deferrable 이 아니라는 뜻이다.
        Long inquiryId = newInquiryId();
        StagedChange first = stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, "ORD-RACE", 10_000, "첫 제안", null));
        StagedChange second = stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, "ORD-RACE", 10_000, "두 번째 제안", null));

        first.approve("counselor-1", null, null);
        stagedChangeRepository.saveAndFlush(first);

        second.approve("counselor-2", null, null);
        assertThatThrownBy(() -> stagedChangeRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
