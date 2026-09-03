package com.aicsassistant.staging.infra;

import static org.assertj.core.api.Assertions.assertThat;

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
}
