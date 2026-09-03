package com.aicsassistant.analysis.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.staging.domain.ChangeType;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StageRefundToolTest {

    @Mock
    StagedChangeRepository stagedChangeRepository;

    private StageRefundTool tool() {
        return new StageRefundTool(stagedChangeRepository, 7L);
    }

    @Test
    void savesPendingProposalAndSaysItIsNotExecuted() {
        when(stagedChangeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = tool().execute(new StageRefundTool.Input(
                "ORD-20260405-002", 45_000, "배송완료 4일 경과", "반품 정책 3조"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data())
                .contains("ORD-20260405-002")
                .contains("45,000")
                .contains("아직 실행되지 않았")
                .contains("상담사 승인");

        ArgumentCaptor<StagedChange> captor = ArgumentCaptor.forClass(StagedChange.class);
        verify(stagedChangeRepository).save(captor.capture());
        StagedChange saved = captor.getValue();
        assertThat(saved.getInquiryId()).isEqualTo(7L);
        assertThat(saved.getChangeType()).isEqualTo(ChangeType.REFUND);
        assertThat(saved.getAmount()).isEqualTo(45_000);
    }

    @Test
    void rejectsMissingOrderId() {
        ToolResult result = tool().execute(new StageRefundTool.Input(" ", 45_000, "사유", null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.errorMessage()).contains("orderId");
        verify(stagedChangeRepository, never()).save(any());
    }

    @Test
    void rejectsNonPositiveAmount() {
        ToolResult result = tool().execute(new StageRefundTool.Input("ORD-A", 0, "사유", null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.errorMessage()).contains("amount");
    }

    @Test
    void rejectsNullAmount() {
        ToolResult result = tool().execute(new StageRefundTool.Input("ORD-A", null, "사유", null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
    }

    @Test
    void rejectsBlankReason() {
        ToolResult result = tool().execute(new StageRefundTool.Input("ORD-A", 45_000, "  ", null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.errorMessage()).contains("reason");
    }

    @Test
    void exposesSurfaceFieldsForLlm() {
        StageRefundTool tool = tool();

        assertThat(tool.name()).isEqualTo("stage_refund");
        assertThat(tool.inputType()).isEqualTo(StageRefundTool.Input.class);
        assertThat(tool.inputSchema()).contains("orderId").contains("amount").contains("reason");
        assertThat(tool.whenToUse()).isNotBlank();
        assertThat(tool.usageBoundary()).contains("check_order_status");
        assertThat(tool.successOutputHint()).contains("승인");
        assertThat(tool.failureBehavior()).contains("PERMISSION");
    }
}
