package com.aicsassistant.ui.viewmodel;

import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import java.time.LocalDateTime;
import java.util.List;

public record InquiryDetailViewModel(
        InquiryDetailResponse inquiry,
        List<EvidenceChunkView> evidenceChunks,
        List<MessageView> messages,
        List<AgentStepView> agentSteps
) {
    public static InquiryDetailViewModel from(
            InquiryDetailResponse inquiry,
            List<EvidenceChunkView> evidenceChunks,
            List<InquiryMessage> messages,
            List<AgentStepView> agentSteps
    ) {
        List<MessageView> messageViews = messages.stream()
                .map(m -> new MessageView(m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();
        return new InquiryDetailViewModel(inquiry, List.copyOf(evidenceChunks), messageViews, List.copyOf(agentSteps));
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

    public record MessageView(
            InquiryMessageRole role,
            String content,
            LocalDateTime createdAt
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
