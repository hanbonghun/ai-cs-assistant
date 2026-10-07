package com.aicsassistant.staging.application;

import com.aicsassistant.staging.domain.ChangeType;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.domain.StagedChangeStatus;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트가 제안을 올리는 입구. 접수만 하고 실행하지 않는다 — 실행은 {@link StagedChangeApprovalService} 에서만.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StagedChangeProposalService {

    private final StagedChangeRepository stagedChangeRepository;

    /** 환불 제안을 접수하고 제안 id 를 돌려준다. */
    @Transactional
    public Long proposeRefund(Long inquiryId, String orderId, int amount, String reason, String policyBasis) {
        return stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, orderId, amount, reason, policyBasis)).getId();
    }

    public boolean hasPendingProposal(String orderId) {
        return stagedChangeRepository.existsByOrderIdAndStatus(orderId, StagedChangeStatus.PENDING);
    }
}
