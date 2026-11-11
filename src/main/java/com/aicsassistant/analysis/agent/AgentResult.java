package com.aicsassistant.analysis.agent;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import java.util.List;

/**
 * 에이전트 실행 결과. 두 가지 경우만 존재한다:
 * - FinalAnswer: 충분한 정보를 확보해 최종 답변을 생성한 경우
 * - FollowUpQuestion: 정보가 부족해 고객에게 추가 질문이 필요한 경우
 */
public sealed interface AgentResult {

    record FinalAnswer(
            String answer,
            String category,
            String urgency,
            boolean needsHumanReview,
            boolean needsEscalation,
            boolean fraudRiskFlag,
            String reason,
            List<AgentStep> steps,
            List<RetrievedManualChunkDto> retrievedChunks,
            int totalTokens,
            List<RetrievedManualChunkDto> counselorReferences
    ) implements AgentResult {

        /** 상담사 참고 근거 없이 만든다. 참고 근거는 상담사에게 넘어간 뒤에만 붙는다 ({@link #withCounselorReferences}). */
        public FinalAnswer(String answer, String category, String urgency, boolean needsHumanReview,
                           boolean needsEscalation, boolean fraudRiskFlag, String reason, List<AgentStep> steps,
                           List<RetrievedManualChunkDto> retrievedChunks, int totalTokens) {
            this(answer, category, urgency, needsHumanReview, needsEscalation, fraudRiskFlag, reason, steps,
                    retrievedChunks, totalTokens, List.of());
        }

        /** 상담사에게 넘어가는 건인가 — 고객에게 자동으로 나가지 않는다. */
        public boolean routedToCounselor() {
            return needsHumanReview || needsEscalation;
        }

        /**
         * 상담사 전용 문서를 참고 근거로 붙인 사본. 모델이 본 근거({@code retrievedChunks})와 섞지 않는다 —
         * 모델은 이 문서를 보지 않았고, 이 건은 고객에게 자동으로 나가지 않는다 (ADR 0012).
         */
        public FinalAnswer withCounselorReferences(List<RetrievedManualChunkDto> references) {
            return new FinalAnswer(answer, category, urgency, needsHumanReview, needsEscalation, fraudRiskFlag,
                    reason, steps, retrievedChunks, totalTokens, List.copyOf(references));
        }

        /** 상담사 화면·분석 로그에 남길 근거 — 모델이 본 것 뒤에 상담사 전용 참고 근거. */
        public List<RetrievedManualChunkDto> evidenceForCounselor() {
            if (counselorReferences.isEmpty()) {
                return retrievedChunks;
            }
            List<RetrievedManualChunkDto> all = new java.util.ArrayList<>(retrievedChunks);
            all.addAll(counselorReferences);
            return List.copyOf(all);
        }

        /**
         * 근거 없이 자동 답변하려던 결과를 상담사 이관으로 바꾼 사본. 모델 초안은 버린다 — 근거 없이 쓴
         * 일반 지식이라 상담사 화면에 남겨도 그대로 전송될 위험만 있다 (ADR 0011).
         */
        public FinalAnswer withoutEvidence(String briefing) {
            return new FinalAnswer(briefing, category, urgency, true, needsEscalation, fraudRiskFlag,
                    "[근거 없음 — 자동 답변 차단] " + reason, steps, retrievedChunks, totalTokens, counselorReferences);
        }

        /** 상담사 검토를 강제한 사본 — 스텝 소진 후 강제 종료 경로에서 사용한다. */
        public FinalAnswer withHumanReview() {
            return new FinalAnswer(answer, category, urgency, true, needsEscalation, fraudRiskFlag,
                    reason, steps, retrievedChunks, totalTokens, counselorReferences);
        }
    }

    record FollowUpQuestion(
            String question,
            List<AgentStep> steps,
            int totalTokens
    ) implements AgentResult {}
}
