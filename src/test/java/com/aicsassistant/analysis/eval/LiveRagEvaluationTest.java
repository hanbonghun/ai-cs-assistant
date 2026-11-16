package com.aicsassistant.analysis.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.agent.AgentResult;
import com.aicsassistant.analysis.agent.AgentStep;
import com.aicsassistant.analysis.agent.InquiryAgentService;
import com.aicsassistant.analysis.application.ManualRetrievalService;
import com.aicsassistant.analysis.application.PromptFactory;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.analysis.eval.LiveEvalScorer.Outcome;
import com.aicsassistant.common.config.AiProperties;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.llm.ChatMessage;
import com.aicsassistant.llm.ClaudeClient;
import com.aicsassistant.llm.LlmClient;
import com.aicsassistant.llm.LlmResponse;
import com.aicsassistant.llm.OpenAiClient;
import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.order.application.OrderService;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 임베딩(text-embedding-3-small)과 실제 모델(gpt-4.1-mini)로 50건 평가셋을 돌린다.
 *
 * <p>{@code ManualRetrievalEvaluationTest} 와 역할이 다르다. 그쪽은 벡터를 고정해 <b>검색 로직의
 * 회귀</b>만 본다. 이쪽은 운영과 같은 코퍼스(시드 매뉴얼 10종 + 평가용 픽스처)를 실제로 임베딩하고
 * 운영과 같은 에이전트 루프를 태워, <b>검색 품질과 생성 품질을 단계별로 따로</b> 기록한다.
 * 근거는 ADR 0010.
 *
 * <p>비용이 드는 실행이라 기본 {@code test} 에서 빠진다. {@code ./gradlew liveEval} 로만 돈다.
 * 단언은 "끝까지 돌았다" 뿐이다 — 수치는 판정이 아니라 기록이고, 이전 실행과 나란히 비교한다.
 */
@Tag("live-eval")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@SpringBootTest(properties = "app.langfuse.enabled=false")
@Import(LiveRagEvaluationTest.UsageRecordingConfig.class)
class LiveRagEvaluationTest extends PostgresVectorIntegrationTest {

    private static final Path EVAL_SET = Path.of("src/test/resources/eval/live/live-eval-set.jsonl");
    private static final Path RUNS_DIR = Path.of("eval/runs");

    @Autowired ManualService manualService;
    @Autowired ManualRetrievalService retrievalService;
    @Autowired InquiryAgentService agentService;
    @Autowired InquiryRepository inquiryRepository;
    @Autowired PromptFactory promptFactory;
    @Autowired AiProperties aiProperties;
    @Autowired UsageRecordingLlmClient llm;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();
    private LiveEvalJudge judge;

    @Test
    void runLiveEvaluation() throws Exception {
        LocalDate today = LocalDate.now(OrderService.ZONE);
        List<LiveEvalCase> cases = selectCases(LiveEvalCase.load(EVAL_SET, today));
        boolean judgeEnabled = !"false".equals(System.getProperty("liveEval.judge"));
        // 키가 없으면 50건을 다 돈 뒤가 아니라 시작 전에 실패한다
        judge = judgeEnabled ? LiveEvalJudge.fromSystemProperties(mapper) : null;

        LiveEvalCorpus.seed(manualService, today);

        List<Map<String, Object>> results = new ArrayList<>();
        for (LiveEvalCase c : cases) {
            System.out.printf("[live-eval] %s %s%n", c.id(), c.query());
            results.add(evaluate(c, today, judgeEnabled));
        }

        Map<String, Object> meta = runMeta(today, cases.size(), judgeEnabled);
        Path runDir = LiveEvalReport.write(RUNS_DIR, meta, results, mapper);
        System.out.println("[live-eval] 결과: " + runDir.toAbsolutePath());

        assertThat(results).hasSize(cases.size());
    }

    // ---------- 케이스 한 건 ----------

    private Map<String, Object> evaluate(LiveEvalCase c, LocalDate today, boolean judgeEnabled) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.id());
        row.put("type", c.type().name());
        row.put("query", c.query());

        // 1) 검색 단독 — 고객 원문 그대로. 에이전트의 질의 재작성과 분리해 검색기만 본다.
        long t0 = System.nanoTime();
        List<RetrievedManualChunkDto> direct = retrievalService.retrieve(c.query());
        long directMs = (System.nanoTime() - t0) / 1_000_000;
        List<String> directContents = direct.stream().map(RetrievedManualChunkDto::content).toList();
        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("latencyMs", directMs);
        retrieval.put("evidenceRanks", LiveEvalScorer.evidenceRanks(c.expectedEvidence(), directContents));
        retrieval.put("resultCount", direct.size());
        retrieval.put("staleLeaks", leaks(c.staleMarkers(), directContents));
        retrieval.put("chunks", direct.stream().map(this::chunkSummary).toList());
        row.put("retrieval", retrieval);

        // 2) 에이전트 — 운영 경로 그대로.
        Map<String, Object> agent = new LinkedHashMap<>();
        UsageRecordingLlmClient.Usage before = llm.snapshot();
        long t1 = System.nanoTime();
        Outcome outcome;
        String answer = "";
        List<AgentStep> steps = List.of();
        List<RetrievedManualChunkDto> collected = List.of();
        try {
            Inquiry inquiry = inquiryRepository.save(Inquiry.create(c.customerId(), titleOf(c.query()), c.query()));
            AgentResult result = agentService.run(inquiry, List.of());
            if (result instanceof AgentResult.FinalAnswer f) {
                outcome = f.needsHumanReview() || f.needsEscalation() ? Outcome.FINAL_HUMAN : Outcome.FINAL_AUTO;
                answer = f.answer();
                steps = f.steps();
                collected = f.retrievedChunks();
                agent.put("needsHumanReview", f.needsHumanReview());
                agent.put("needsEscalation", f.needsEscalation());
                agent.put("category", f.category());
                agent.put("reason", f.reason());
                // 모델이 보지 않은 상담사 전용 참고 근거 (ADR 0012). 고객에게 나가는 답의 근거와 섞지 않는다
                agent.put("counselorReferenceTitles", f.counselorReferences().stream()
                        .map(RetrievedManualChunkDto::manualDocumentTitle).distinct().toList());
            } else {
                AgentResult.FollowUpQuestion q = (AgentResult.FollowUpQuestion) result;
                outcome = Outcome.FOLLOW_UP;
                answer = q.question();
                steps = q.steps();
            }
        } catch (RuntimeException e) {
            outcome = Outcome.ERROR;
            agent.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        long agentMs = (System.nanoTime() - t1) / 1_000_000;
        UsageRecordingLlmClient.Usage used = llm.snapshot().minus(before);

        List<String> collectedContents = collected.stream().map(RetrievedManualChunkDto::content).toList();
        List<String> tools = steps.stream().map(AgentStep::action).toList();
        agent.put("outcome", outcome.name());
        agent.put("answer", answer);
        agent.put("tools", tools);
        agent.put("toolInputs", steps.stream().map(AgentStep::actionInput).toList());
        // 관측 원문 — 기준선에는 없어서 재채점 때 근거 텍스트를 복원할 수 없었다
        agent.put("observations", steps.stream().map(AgentStep::observation).toList());
        agent.put("evidenceCovered", LiveEvalScorer.evidenceRanks(c.expectedEvidence(), collectedContents).stream()
                .map(r -> r > 0).toList());
        agent.put("staleLeaks", leaks(c.staleMarkers(), collectedContents));
        agent.put("collectedTitles", collected.stream().map(RetrievedManualChunkDto::manualDocumentTitle).distinct().toList());
        agent.put("latencyMs", agentMs);
        agent.put("llmCalls", used.calls());
        agent.put("promptTokens", used.promptTokens());
        agent.put("completionTokens", used.completionTokens());
        agent.put("cachedTokens", used.cachedTokens());
        row.put("agent", agent);

        // 3) 채점 — 규칙 기반은 항목별로 따로 남기고 합치지 않는다.
        Map<String, Object> checks = LiveEvalScorer.checks(c, answer, outcome, tools,
                collected.stream().map(RetrievedManualChunkDto::manualDocumentTitle).distinct().toList(), today.toString());
        row.put("checks", checks);
        row.put("expected", Map.of(
                "abstain", c.expectAbstain(),
                "mustCallTools", c.mustCallTools(),
                "routing", c.expectedRouting().name(),
                "mustInclude", c.mustInclude(),
                "forbidden", c.forbidden(),
                "evidence", c.expectedEvidence()));

        // 4) judge — 답이 에이전트가 실제로 본 근거로 뒷받침되는가. 채점자는 에이전트와 따로 고정한다(LiveEvalJudge).
        if (judgeEnabled && outcome != Outcome.ERROR && !answer.isBlank()) {
            String evidence = LiveEvalJudge.evidence(tools, steps.stream().map(AgentStep::observation).toList());
            row.put("judge", judge.judge(c.query(), answer, evidence, today));
        }
        return row;
    }

    // ---------- 보조 ----------

    private List<LiveEvalCase> selectCases(List<LiveEvalCase> all) {
        String ids = System.getProperty("liveEval.ids", "");
        if (ids.isBlank()) {
            return all;
        }
        Set<String> wanted = Arrays.stream(ids.split(",")).map(String::trim).collect(Collectors.toSet());
        return all.stream().filter(c -> wanted.contains(c.id())).toList();
    }

    private static List<String> leaks(List<String> markers, List<String> contents) {
        return markers.stream()
                .filter(m -> contents.stream().anyMatch(content -> LiveEvalScorer.containsAny(content, m)))
                .toList();
    }

    private Map<String, Object> chunkSummary(RetrievedManualChunkDto c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", c.manualDocumentTitle());
        m.put("chunkIndex", c.chunkIndex());
        m.put("vectorScore", c.similarityScore());
        m.put("keywordScore", c.keywordScore());
        m.put("preview", c.content().length() > 60 ? c.content().substring(0, 60) + "…" : c.content());
        return m;
    }

    /** 고객이 쓰는 제목처럼 본문 앞부분을 쓴다. "[평가]" 같은 표식은 모델 판단에 섞이므로 넣지 않는다. */
    private static String titleOf(String query) {
        return query.length() > 30 ? query.substring(0, 30) : query;
    }

    private Map<String, Object> runMeta(LocalDate today, int caseCount, boolean judgeEnabled) {
        List<String> chunkContents = jdbc.queryForList("""
                select mc.content from manual_chunk mc join manual_document md on md.id = mc.manual_document_id
                where mc.active = true and md.active = true order by md.title, mc.chunk_index
                """, String.class);
        Integer docCount = jdbc.queryForObject("select count(*) from manual_document where active = true", Integer.class);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("today", today.toString());
        meta.put("git", LiveEvalReport.gitDescribe());
        meta.put("chatModel", llm.modelName());
        meta.put("embeddingModel", aiProperties.getEmbeddingModel());
        meta.put("promptVersion", promptFactory.promptVersion());
        meta.put("caseCount", caseCount);
        meta.put("caseFilter", System.getProperty("liveEval.ids", ""));
        meta.put("scorerVersion", LiveEvalScorer.VERSION);
        meta.put("label", System.getProperty("liveEval.label", ""));
        meta.put("judge", judgeEnabled ? judge.model() : "off");
        meta.put("activeDocuments", docCount);
        meta.put("activeChunks", chunkContents.size());
        meta.put("corpusSha256", LiveEvalReport.sha256(String.join("\u0000", chunkContents)));
        meta.put("evalSetSha256", LiveEvalReport.sha256File(EVAL_SET));
        return meta;
    }

    // ---------- 토큰 기록 ----------

    /**
     * 실제 채팅 클라이언트({@link OpenAiClient} 또는 {@link ClaudeClient})를 감싸 호출 수와 토큰을 센다. 에이전트 결과에는 합계 토큰만 남아
     * 입력·출력·캐시 몫을 나눌 수 없어서다. 실행은 순차라 스냅샷 차이로 케이스별 몫을 구한다.
     */
    @TestConfiguration
    static class UsageRecordingConfig {

        @Bean
        @Primary
        UsageRecordingLlmClient usageRecordingLlmClient(OpenAiClient openAi, Optional<ClaudeClient> claude) {
            // provider=anthropic 이면 ClaudeClient 가 떠 있다. 임베딩 때문에 OpenAiClient 는 항상 있다.
            return new UsageRecordingLlmClient(claude.<LlmClient>map(c -> c).orElse(openAi));
        }
    }

    static final class UsageRecordingLlmClient implements LlmClient {

        record Usage(int calls, long promptTokens, long completionTokens, long cachedTokens) {
            Usage minus(Usage o) {
                return new Usage(calls - o.calls, promptTokens - o.promptTokens,
                        completionTokens - o.completionTokens, cachedTokens - o.cachedTokens);
            }
        }

        private final LlmClient delegate;
        private int calls;
        private long promptTokens;
        private long completionTokens;
        private long cachedTokens;

        UsageRecordingLlmClient(LlmClient delegate) {
            this.delegate = delegate;
        }

        synchronized Usage snapshot() {
            return new Usage(calls, promptTokens, completionTokens, cachedTokens);
        }

        @Override
        public String complete(String prompt) {
            return complete(List.of(ChatMessage.user(prompt)));
        }

        @Override
        public String complete(List<ChatMessage> messages) {
            return completeWithUsage(messages).content();
        }

        @Override
        public LlmResponse completeWithUsage(List<ChatMessage> messages) {
            LlmResponse response = delegate.completeWithUsage(messages);
            synchronized (this) {
                calls++;
                promptTokens += response.promptTokens();
                completionTokens += response.completionTokens();
                cachedTokens += response.cacheReadTokens();
            }
            return response;
        }

        @Override
        public String modelName() {
            return delegate.modelName();
        }
    }
}
