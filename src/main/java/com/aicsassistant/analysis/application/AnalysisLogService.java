package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.agent.AgentStep;
import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.analysis.dto.CategoryResultDto;
import com.aicsassistant.analysis.dto.DraftAnswerDto;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.dto.UrgencyResultDto;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.analysis.infra.llm.LlmClient;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisLogService {

    private final InquiryAnalysisLogRepository inquiryAnalysisLogRepository;
    private final PromptFactory promptFactory;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    /** 분석 시작을 기록하고 로그 id 를 돌려준다. 종료 시 같은 행을 제자리에서 뒤집는다. */
    @Transactional
    public Long startRunning(Inquiry inquiry) {
        InquiryAnalysisLog entry = InquiryAnalysisLog.running(
                inquiry,
                inquiry.getContent(),
                llmClient.modelName(),
                promptFactory.promptVersion()
        );
        return inquiryAnalysisLogRepository.save(entry).getId();
    }

    @Transactional
    public void completeSuccess(
            Long logId,
            CategoryResultDto category,
            UrgencyResultDto urgency,
            List<RetrievedManualChunkDto> chunks,
            DraftAnswerDto draft,
            List<AgentStep> agentSteps,
            long startedAtMillis,
            int totalTokens
    ) {
        InquiryAnalysisLog entry = requireLog(logId);
        entry.completeSuccess(
                InquiryCategory.valueOf(category.value()),
                UrgencyLevel.valueOf(urgency.value()),
                chunks.stream().map(RetrievedManualChunkDto::id).toList(),
                draft.answer(),
                serializeSteps(agentSteps),
                elapsed(startedAtMillis),
                totalTokens
        );
        inquiryAnalysisLogRepository.save(entry);
    }

    /**
     * 추가 질문으로 끝난 라운드를 마감한다. 분류는 아직 없으므로 category/urgency 는 null 로
     * 남기고, 질문 문구를 generatedDraft 에 넣는다.
     *
     * <p>마감하지 않으면 RUNNING 행이 영구히 남아 스위퍼가 계속 재분석한다.
     */
    @Transactional
    public void completeFollowUp(
            Long logId,
            String question,
            List<AgentStep> agentSteps,
            long startedAtMillis,
            int totalTokens
    ) {
        InquiryAnalysisLog entry = requireLog(logId);
        entry.completeSuccess(
                null,
                null,
                List.of(),
                question,
                serializeSteps(agentSteps),
                elapsed(startedAtMillis),
                totalTokens
        );
        inquiryAnalysisLogRepository.save(entry);
    }

    private String serializeSteps(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception e) {
            log.warn("에이전트 스텝 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 실패 로그. 예전에는 {@code REQUIRES_NEW} 였다 — 바깥 트랜잭션이 롤백돼도 로그를 남기려는
     * 의도였는데, 그 때문에 바깥 커넥션을 쥔 채 두 번째 커넥션을 요구해 풀 데드락 소지가 있었다
     * (prod pool 5 / async pool 8). 경계를 나눈 뒤에는 호출 시점에 열린 트랜잭션이 없다.
     */
    @Transactional
    public void completeFailure(Long logId, RuntimeException exception, long startedAtMillis) {
        inquiryAnalysisLogRepository.findById(logId).ifPresent(entry -> {
            entry.completeFailure(exception.getMessage(), elapsed(startedAtMillis));
            inquiryAnalysisLogRepository.save(entry);
        });
    }

    private InquiryAnalysisLog requireLog(Long logId) {
        return inquiryAnalysisLogRepository.findById(logId)
                .orElseThrow(() -> new IllegalStateException("분석 로그를 찾을 수 없습니다: " + logId));
    }

    @Transactional
    public void rateLatestLog(Long inquiryId, String rating, String reason, String note) {
        inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId)
                .stream()
                .findFirst()
                .ifPresent(logEntry -> {
                    logEntry.rate(rating, reason, note);
                    inquiryAnalysisLogRepository.save(logEntry);
                });
    }

    public java.util.Optional<String> getLatestAgentStepsJson(Long inquiryId) {
        return inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId)
                .stream()
                .findFirst()
                .map(InquiryAnalysisLog::getAgentSteps);
    }

    public java.util.Optional<String> getLatestRetrievedChunkIds(Long inquiryId) {
        return inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId)
                .stream()
                .findFirst()
                .map(InquiryAnalysisLog::getRetrievedChunkIds);
    }

    private long elapsed(long startedAtMillis) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
    }
}
