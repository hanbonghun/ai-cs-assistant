package com.aicsassistant.analysis.agent.tool;

import com.aicsassistant.analysis.agent.AgentTool;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.staging.domain.ChangeType;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.infra.StagedChangeRepository;

/**
 * 환불 제안을 접수하는 도구. <b>환불을 실행하지 않는다.</b>
 *
 * <p>가드레일(provenance·금액·주문상태·중복)은 이 툴이 아니라
 * {@code RefundGuardrailInterceptor}가 검사한다 — 툴은 {@code ToolCallContext}를 볼 수 없다.
 * 여기 도달한 호출은 이미 가드를 통과했으므로 입력 형식만 검증하고 저장한다.
 */
public class StageRefundTool implements AgentTool<StageRefundTool.Input> {

    /** 도구 입력 — 금액은 부분 환불을 허용하므로 결제금액과 다를 수 있다. */
    public record Input(String orderId, Integer amount, String reason, String policyBasis) {}

    private final StagedChangeRepository stagedChangeRepository;
    private final Long inquiryId;

    public StageRefundTool(StagedChangeRepository stagedChangeRepository, Long inquiryId) {
        this.stagedChangeRepository = stagedChangeRepository;
        this.inquiryId = inquiryId;
    }

    @Override
    public String name() {
        return "stage_refund";
    }

    @Override
    public String description() {
        return "Submits a refund proposal for counselor approval. Does NOT execute the refund.";
    }

    @Override
    public String whenToUse() {
        return "Call when the customer requests a refund AND you have already looked up the order with "
                + "check_order_status AND the policy supports a refund. Submit the amount you believe is "
                + "correct with your reasoning — a counselor decides whether to execute it.";
    }

    @Override
    public String usageBoundary() {
        return "Do NOT use for: (1) orders you have not looked up with check_order_status in this "
                + "conversation (the call will be blocked), (2) order cancellation or exchange (not supported "
                + "yet — set needsHumanReview: true instead), (3) telling the customer the refund is done. "
                + "This tool only files a proposal; nothing is refunded until a counselor approves.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public String inputSchema() {
        return "{\"orderId\": \"string (required) — order looked up in this conversation\", "
                + "\"amount\": \"integer (required) — KRW to refund, must be > 0 and <= the order's paid amount\", "
                + "\"reason\": \"string (required) — Korean explanation of how you arrived at this amount\", "
                + "\"policyBasis\": \"string (optional) — the policy clause you relied on\"}";
    }

    @Override
    public String successOutputHint() {
        return "A Korean confirmation that the proposal was FILED and is awaiting 상담사 승인 — "
                + "it explicitly states the refund has not been executed. Never tell the customer the refund "
                + "is complete based on this result.";
    }

    @Override
    public String failureBehavior() {
        return "PERMISSION (provenance / order state / duplicate): do NOT retry. Produce finalAnswer with "
                + "needsHumanReview: true, and if the message says to look up the order first, call "
                + "check_order_status before proposing again. "
                + "VALIDATION (amount or reason): fix actionInput and retry once.";
    }

    @Override
    public ToolResult execute(Input input) {
        String orderId = input.orderId() == null ? "" : input.orderId().strip();
        if (orderId.isBlank()) {
            return ToolResult.error(ToolErrorCategory.VALIDATION, false, "'orderId' field is required.");
        }
        if (input.amount() == null || input.amount() <= 0) {
            return ToolResult.error(ToolErrorCategory.VALIDATION, false,
                    "'amount' must be a positive integer (KRW).");
        }
        String reason = input.reason() == null ? "" : input.reason().strip();
        if (reason.isBlank()) {
            return ToolResult.error(ToolErrorCategory.VALIDATION, false,
                    "'reason' field is required — explain how you arrived at this amount.");
        }

        StagedChange saved = stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, orderId, input.amount(), reason, input.policyBasis()));

        return ToolResult.success(
                "환불 제안 #%s 접수됨 (주문 %s, %,d원). 아직 실행되지 않았으며 상담사 승인이 필요합니다."
                        .formatted(saved.getId(), orderId, input.amount()));
    }
}
