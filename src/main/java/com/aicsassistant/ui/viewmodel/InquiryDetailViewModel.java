package com.aicsassistant.ui.viewmodel;

import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import com.aicsassistant.inquiry.dto.InquiryMessageResponse;
import com.aicsassistant.staging.dto.StagedChangeResponse;
import java.util.List;

public record InquiryDetailViewModel(
        InquiryDetailResponse inquiry,
        List<EvidenceChunkView> evidenceChunks,
        List<InquiryMessageResponse> messages,
        List<AgentStepView> agentSteps,
        List<StagedChangeResponse> stagedChanges
) {
    public static InquiryDetailViewModel from(
            InquiryDetailResponse inquiry,
            List<EvidenceChunkView> evidenceChunks,
            List<InquiryMessageResponse> messages,
            List<AgentStepView> agentSteps,
            List<StagedChangeResponse> stagedChanges
    ) {
        return new InquiryDetailViewModel(inquiry, List.copyOf(evidenceChunks), List.copyOf(messages),
                List.copyOf(agentSteps), List.copyOf(stagedChanges));
    }

    public record EvidenceChunkView(
            Long id,
            Long manualDocumentId,
            String manualDocumentTitle,
            String manualCategory,
            Integer chunkIndex,
            Integer documentVersion,
            Integer tokenCount,
            String content
    ) {}

    /** 상담사가 읽기 쉬운 형태로 변환된 에이전트 스텝 뷰 */
    public record AgentStepView(
            String actionLabel,          // "정책 문서 검색", "주문 조회" 등
            String thought,              // LLM 판단 근거 (원문 그대로)
            String observationSummary,   // 툴 결과 요약 (최대 200자)
            List<DocRef> referencedDocs  // 검색된 문서 링크 (search_manual 스텝만)
    ) {
        public record DocRef(Long docId, String title, String category) {}
    }
}
