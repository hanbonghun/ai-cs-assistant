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
            int totalTokens
    ) implements AgentResult {

        /**
         * 근거 없이 자동 답변하려던 결과를 상담사 이관으로 바꾼 사본. 모델 초안은 버린다 — 근거 없이 쓴
         * 일반 지식이라 상담사 화면에 남겨도 그대로 전송될 위험만 있다 (ADR 0011).
         */
        public FinalAnswer withoutEvidence(String briefing) {
            return new FinalAnswer(briefing, category, urgency, true, needsEscalation, fraudRiskFlag,
                    "[근거 없음 — 자동 답변 차단] " + reason, steps, retrievedChunks, totalTokens);
        }

        /** 상담사 검토를 강제한 사본 — 스텝 소진 후 강제 종료 경로에서 사용한다. */
        public FinalAnswer withHumanReview() {
            return new FinalAnswer(answer, category, urgency, true, needsEscalation, fraudRiskFlag,
                    reason, steps, retrievedChunks, totalTokens);
        }
    }

    record FollowUpQuestion(
            String question,
            List<AgentStep> steps,
            int totalTokens
    ) implements AgentResult {}
}
