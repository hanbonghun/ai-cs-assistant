package com.aicsassistant.staging.application;

import static com.aicsassistant.staging.domain.RefundGuardrails.ALREADY_REFUNDED_STATUS;
import static com.aicsassistant.staging.domain.RefundGuardrails.REFUND_BLOCKING_STATUSES;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.aicsassistant.order.InMemoryOrderRepository.OrderInfo;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.domain.StagedChangeStatus;
import com.aicsassistant.staging.dto.StagedChangeDecisionRequest;
import com.aicsassistant.staging.dto.StagedChangeResponse;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담사의 승인 표면. 에이전트가 올린 제안은 여기를 통해서만 실행된다.
 *
 * <p>가드레일을 승인 시점에 다시 검사한다 — 제안 시점과 승인 시점 사이에 주문 상태가 바뀔 수 있다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StagedChangeApprovalService {

    private final StagedChangeRepository stagedChangeRepository;
    private final InquiryRepository inquiryRepository;
    private final InquiryMessageRepository messageRepository;
    private final InMemoryOrderRepository orderRepository;

    public List<StagedChangeResponse> findByInquiry(Long inquiryId) {
        return stagedChangeRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId).stream()
                .map(StagedChangeResponse::from)
                .toList();
    }

    @Transactional
    public StagedChangeResponse approve(Long inquiryId, Long changeId, StagedChangeDecisionRequest request) {
        StagedChange change = loadForInquiry(inquiryId, changeId);
        Inquiry inquiry = loadInquiry(inquiryId);

        // 이미 결정된 제안이면 가드레일 재검사보다 먼저 걸러야 한다 — 예를 들어 승인 후 주문이
        // 환불완료로 바뀐 상태에서 재시도하면, 가드레일이 먼저 걸려 ALREADY_DECIDED 대신
        // GUARDRAIL_FAILED 를 던지게 된다. 어떤 결정이 이미 내려졌는지가 우선이다.
        if (change.getStatus() != StagedChangeStatus.PENDING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALREADY_DECIDED",
                    "이미 " + change.getStatus() + " 상태인 제안입니다. (ALREADY_DECIDED)");
        }

        // 상담사가 금액을 고쳤다면 그 금액으로 검사한다 — 제안 금액이 아니라 실제로 나갈 금액이 기준이다
        int finalAmount = request.approvedAmount() != null ? request.approvedAmount() : change.getAmount();
        reCheckGuardrails(change, inquiry, finalAmount);

        change.approve(request.decidedBy(), request.decisionNote(), request.approvedAmount());
        // ponytail: DB 쓰기(제안 상태·알림 메시지)는 이 트랜잭션 안이지만 mock 주문 변경은 밖이다.
        // 실행을 마지막에 두어 실무상 어긋날 확률을 없앴을 뿐, 실제 결제 시스템이면 아웃박스가 필요하다.
        orderRepository.markRefunded(change.getOrderId());
        messageRepository.save(InquiryMessage.of(inquiryId, InquiryMessageRole.AI,
                "요청하신 환불이 승인되어 처리되었습니다. 주문 %s · 환불 금액 %,d원입니다. 카드 취소는 2~3 영업일이 소요될 수 있습니다."
                        .formatted(change.getOrderId(), change.effectiveAmount())));

        log.info("[StagedChange approved] changeId={} inquiryId={} orderId={} proposed={} final={} by={}",
                changeId, inquiryId, change.getOrderId(), change.getAmount(),
                change.effectiveAmount(), request.decidedBy());
        return StagedChangeResponse.from(change);
    }

    @Transactional
    public StagedChangeResponse reject(Long inquiryId, Long changeId, StagedChangeDecisionRequest request) {
        StagedChange change = loadForInquiry(inquiryId, changeId);

        change.reject(request.decidedBy(), request.decisionNote());

        log.info("[StagedChange rejected] changeId={} inquiryId={} by={} note={}",
                changeId, inquiryId, request.decidedBy(), request.decisionNote());
        return StagedChangeResponse.from(change);
    }

    /**
     * 승인 시점 재검사. provenance 는 에이전트 실행 세션 개념이라 대응물이 없고, 중복 검사는 이 제안
     * 자신이 유일한 PENDING 이라 무의미하므로 금액·주문상태 2종만 본다.
     *
     * <p>금액은 제안값이 아니라 상담사가 확정한 최종 금액({@code finalAmount})으로 검사한다.
     */
    private void reCheckGuardrails(StagedChange change, Inquiry inquiry, int finalAmount) {
        OrderInfo order = orderRepository
                .findById(change.getOrderId(), inquiry.getCustomerIdentifier())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "GUARDRAIL_FAILED",
                        "주문 정보를 확인할 수 없어 승인할 수 없습니다. (GUARDRAIL_FAILED)"));

        if (REFUND_BLOCKING_STATUSES.contains(order.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GUARDRAIL_FAILED",
                    "주문 상태가 [" + order.status() + "]로 바뀌어 승인할 수 없습니다. (GUARDRAIL_FAILED)");
        }
        if (order.status().equals(ALREADY_REFUNDED_STATUS)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GUARDRAIL_FAILED",
                    "이미 환불된 주문입니다. (GUARDRAIL_FAILED)");
        }
        if (finalAmount > order.amount()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GUARDRAIL_FAILED",
                    "승인 금액 %,d원이 결제금액 %,d원을 초과해 승인할 수 없습니다. (GUARDRAIL_FAILED)"
                            .formatted(finalAmount, order.amount()));
        }
    }

    private StagedChange loadForInquiry(Long inquiryId, Long changeId) {
        StagedChange change = stagedChangeRepository.findById(changeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "STAGED_CHANGE_NOT_FOUND",
                        "제안을 찾을 수 없습니다. (STAGED_CHANGE_NOT_FOUND)"));
        // 경로 위조로 남의 문의 제안을 승인하는 것을 막는다
        if (!change.getInquiryId().equals(inquiryId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "STAGED_CHANGE_NOT_FOUND",
                    "제안을 찾을 수 없습니다. (STAGED_CHANGE_NOT_FOUND)");
        }
        return change;
    }

    private Inquiry loadInquiry(Long inquiryId) {
        return inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND",
                        "Inquiry not found"));
    }
}
