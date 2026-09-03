package com.aicsassistant.staging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.aicsassistant.staging.domain.ChangeType;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.domain.StagedChangeStatus;
import com.aicsassistant.staging.dto.StagedChangeDecisionRequest;
import com.aicsassistant.staging.dto.StagedChangeResponse;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class StagedChangeApprovalServiceTest extends PostgresVectorIntegrationTest {

    private static final String ORDER_ID = "ORD-20260405-002";   // cust-001 소유, 배송완료, 45,000원

    @Autowired StagedChangeApprovalService approvalService;
    @Autowired StagedChangeRepository stagedChangeRepository;
    @Autowired InquiryRepository inquiryRepository;
    @Autowired InquiryMessageRepository messageRepository;
    @Autowired InMemoryOrderRepository orderRepository;

    @org.junit.jupiter.api.AfterEach
    void restoreOrderState() {
        orderRepository.resetForTest();
    }

    private StagedChange pendingProposal(int amount) {
        Long inquiryId = inquiryRepository
                .save(Inquiry.create("cust-001", "문의", "환불 요청", null, null, ORDER_ID)).getId();
        return stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, ORDER_ID, amount, "배송완료 4일 경과", "반품 정책 3조"));
    }

    private StagedChange pendingProposal(String customerIdentifier, String orderId, int amount) {
        Long inquiryId = inquiryRepository
                .save(Inquiry.create(customerIdentifier, "문의", "환불 요청", null, null, orderId)).getId();
        return stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, orderId, amount, "배송완료 4일 경과", "반품 정책 3조"));
    }

    @Test
    void approveExecutesRefundAndNotifiesCustomer() {
        StagedChange proposal = pendingProposal(45_000);

        StagedChangeResponse response = approvalService.approve(
                proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null, null));

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.decidedBy()).isEqualTo("counselor-demo");
        assertThat(response.approvedAmount()).isNull();   // 금액을 고치지 않은 승인

        StagedChange reloaded = stagedChangeRepository.findById(proposal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(StagedChangeStatus.APPROVED);
        assertThat(reloaded.getDecidedAt()).isNotNull();

        assertThat(orderRepository.findById(ORDER_ID, "cust-001").orElseThrow().status())
                .isEqualTo("환불완료");

        List<InquiryMessage> messages =
                messageRepository.findByInquiryIdOrderByCreatedAtAsc(proposal.getInquiryId());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getRole()).isEqualTo(InquiryMessageRole.AI);
        assertThat(messages.get(0).getContent()).contains("45,000").contains("환불");
    }

    @Test
    void rejectLeavesOrderAndSendsNoMessage() {
        StagedChange proposal = pendingProposal(45_000);

        StagedChangeResponse response = approvalService.reject(
                proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", "배송 기록과 불일치", null));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.decisionNote()).isEqualTo("배송 기록과 불일치");
        assertThat(orderRepository.findById(ORDER_ID, "cust-001").orElseThrow().status())
                .isEqualTo("배송완료");
        assertThat(messageRepository.findByInquiryIdOrderByCreatedAtAsc(proposal.getInquiryId())).isEmpty();
    }

    @Test
    void secondDecisionIsRejected() {
        StagedChange proposal = pendingProposal(45_000);
        approvalService.approve(proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null, null));

        assertThatThrownBy(() -> approvalService.approve(proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("other", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ALREADY_DECIDED");
    }

    @Test
    void rejectsMismatchedInquiryId() {
        StagedChange proposal = pendingProposal(45_000);
        Long otherInquiryId = inquiryRepository.save(Inquiry.create("cust-002", "다른 문의", "내용")).getId();

        assertThatThrownBy(() -> approvalService.approve(otherInquiryId, proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("STAGED_CHANGE_NOT_FOUND");
    }

    @Test
    void reChecksGuardrailsAtApprovalTime() {
        StagedChange proposal = pendingProposal(45_000);
        // 제안 이후 주문이 이미 환불되었다면 승인이 거절되어야 한다
        orderRepository.markRefunded(ORDER_ID);

        assertThatThrownBy(() -> approvalService.approve(proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GUARDRAIL_FAILED");

        assertThat(stagedChangeRepository.findById(proposal.getId()).orElseThrow().getStatus())
                .isEqualTo(StagedChangeStatus.PENDING);
        assertThat(messageRepository.findByInquiryIdOrderByCreatedAtAsc(proposal.getInquiryId())).isEmpty();
    }

    @Test
    void reChecksGuardrailsForBlockingOrderStatusAtApprovalTime() {
        // ORD-20260401-004 는 cust-002 소유이며 시드 데이터부터 이미 "취소처리중" — 별도 setter 없이
        // 차단 상태 재검사(REFUND_BLOCKING_STATUSES)를 검증할 수 있는 유일한 시드 주문이다.
        StagedChange proposal = pendingProposal("cust-002", "ORD-20260401-004", 128_000);

        assertThatThrownBy(() -> approvalService.approve(proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GUARDRAIL_FAILED");

        assertThat(stagedChangeRepository.findById(proposal.getId()).orElseThrow().getStatus())
                .isEqualTo(StagedChangeStatus.PENDING);
        assertThat(messageRepository.findByInquiryIdOrderByCreatedAtAsc(proposal.getInquiryId())).isEmpty();
    }

    @Test
    void approveWithEditedAmountRefundsTheEditedAmount() {
        StagedChange proposal = pendingProposal(45_000);

        StagedChangeResponse response = approvalService.approve(
                proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", "포장 훼손분만 인정", 32_000));

        assertThat(response.amount()).isEqualTo(45_000);           // AI 제안은 이력으로 남는다
        assertThat(response.approvedAmount()).isEqualTo(32_000);

        // approved_amount 컬럼 round-trip 확인 — DTO 응답값이 아니라 DB 재조회값으로 검증한다
        StagedChange reloaded = stagedChangeRepository.findById(proposal.getId()).orElseThrow();
        assertThat(reloaded.getApprovedAmount()).isEqualTo(32_000);

        List<InquiryMessage> messages =
                messageRepository.findByInquiryIdOrderByCreatedAtAsc(proposal.getInquiryId());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getContent()).contains("32,000").doesNotContain("45,000");
    }

    @Test
    void rejectsEditedAmountAboveOrderTotal() {
        StagedChange proposal = pendingProposal(45_000);

        assertThatThrownBy(() -> approvalService.approve(proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null, 999_000)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GUARDRAIL_FAILED");

        assertThat(stagedChangeRepository.findById(proposal.getId()).orElseThrow().getStatus())
                .isEqualTo(StagedChangeStatus.PENDING);
        assertThat(orderRepository.findById(ORDER_ID, "cust-001").orElseThrow().status())
                .isEqualTo("배송완료");
        assertThat(messageRepository.findByInquiryIdOrderByCreatedAtAsc(proposal.getInquiryId())).isEmpty();
    }

    @Test
    void rejectsNonPositiveEditedAmountAtApprovalTime() {
        // 상담사가 금액을 -5,000원으로 고쳐 승인 시도하는 경우. 이 경로는 사실 도메인의
        // INVALID_APPROVED_AMOUNT 로도 막히지만, 서비스의 재검사가 change.approve(...) 호출보다
        // 먼저 실행되므로 GUARDRAIL_FAILED 가 먼저 나간다.
        StagedChange proposal = pendingProposal(45_000);

        assertThatThrownBy(() -> approvalService.approve(proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null, -5_000)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GUARDRAIL_FAILED");

        assertThat(stagedChangeRepository.findById(proposal.getId()).orElseThrow().getStatus())
                .isEqualTo(StagedChangeStatus.PENDING);
        assertThat(messageRepository.findByInquiryIdOrderByCreatedAtAsc(proposal.getInquiryId())).isEmpty();
    }

    @Test
    void rejectsNonPositiveProposedAmountApprovedWithoutEditing() {
        // amount <= 0 인 staged_change 행 자체는 StageRefundTool 을 거치지 않은 경로(직접 DB 조작,
        // 데이터 수정, 미래의 다른 제안 경로)에서만 생길 수 있다 — StagedChange.propose 는 금액을
        // 검증하지 않으므로 테스트가 직접 이런 행을 만들 수 있다. approvedAmount 를 지정하지 않으면
        // (상담사가 금액을 고치지 않은 기본 승인) finalAmount 가 이 음수 제안 금액으로 그대로
        // 떨어지고, approve(...) 안의 INVALID_APPROVED_AMOUNT 검사는 approvedAmount == null 이라
        // 건드리지 않는다 — 승인 시점 재검사가 이 구멍을 막는 유일한 방어선이다.
        StagedChange proposal = pendingProposal(-5_000);

        assertThatThrownBy(() -> approvalService.approve(proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GUARDRAIL_FAILED");

        assertThat(stagedChangeRepository.findById(proposal.getId()).orElseThrow().getStatus())
                .isEqualTo(StagedChangeStatus.PENDING);
        assertThat(orderRepository.findById(ORDER_ID, "cust-001").orElseThrow().status())
                .isEqualTo("배송완료");
        assertThat(messageRepository.findByInquiryIdOrderByCreatedAtAsc(proposal.getInquiryId())).isEmpty();
    }

    @Test
    void findByInquiryReturnsProposals() {
        StagedChange proposal = pendingProposal(45_000);

        List<StagedChangeResponse> found = approvalService.findByInquiry(proposal.getInquiryId());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).orderId()).isEqualTo(ORDER_ID);
        assertThat(found.get(0).status()).isEqualTo("PENDING");
    }
}
