package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.analysis.domain.AnalysisStatus;
import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.analysis.infra.llm.ChatMessage;
import com.aicsassistant.analysis.infra.llm.EmbeddingClient;
import com.aicsassistant.analysis.infra.llm.LlmClient;
import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@Import(InquiryAnalysisServiceTest.FakeAiConfig.class)
class InquiryAnalysisServiceTest extends PostgresVectorIntegrationTest {

    @Autowired
    InquiryAnalysisService inquiryAnalysisService;

    @Autowired
    InquiryRepository inquiryRepository;

    @Autowired
    InquiryAnalysisLogRepository inquiryAnalysisLogRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    FakeLlmClient fakeLlmClient;

    @BeforeEach
    void setUp() {
        fakeLlmClient.reset();
        jdbcTemplate.update("delete from inquiry_analysis_log");
        jdbcTemplate.update("delete from manual_chunk");
        jdbcTemplate.update("delete from manual_document");
        jdbcTemplate.update("delete from inquiry");
    }

    @Test
    void analyzeUpdatesInquiryAndWritesLog() {
        Inquiry savedInquiry = inquiryRepository.save(Inquiry.create("cust-001", "문의", "멤버십 환불이 가능한가요?"));
        seedManualChunk("환불은 영업일 기준 3일 내 처리됩니다.");

        // Step 1: agent calls search_manual tool
        fakeLlmClient.enqueue("""
                {"thought":"환불 관련 정책을 먼저 검색해야 합니다.","action":"search_manual","actionInput":{"query":"멤버십 환불 정책"}}
                """);
        // Step 2: agent produces final answer after seeing the retrieved chunks
        fakeLlmClient.enqueue("""
                {"thought":"정책 문서를 확인했습니다. 최종 답변을 작성합니다.","finalAnswer":"안녕하세요. 환불 규정에 따라 ...","category":"REFUND","urgency":"MEDIUM","needsHumanReview":true,"needsEscalation":false,"fraudRiskFlag":false,"reason":"refund request"}
                """);

        InquiryAnalysisResponse response = inquiryAnalysisService.analyze(savedInquiry.getId());

        assertThat(response.category().value()).isEqualTo(InquiryCategory.REFUND.name());
        assertThat(response.category().reason()).isEqualTo("refund request");
        assertThat(response.category().needsHumanReview()).isTrue();
        assertThat(response.retrievedChunks()).isNotEmpty();

        Inquiry reloaded = inquiryRepository.findById(savedInquiry.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.AI_PROCESSED);
        assertThat(reloaded.getCategory()).isEqualTo(InquiryCategory.REFUND);
        assertThat(reloaded.getAiDraftAnswer()).isEqualTo("안녕하세요. 환불 규정에 따라 ...");

        assertThat(inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(savedInquiry.getId()))
                .isNotEmpty()
                .first()
                .extracting(log -> log.getAnalysisStatus())
                .isEqualTo(AnalysisStatus.SUCCESS);
    }

    @Test
    void analyzeFailureStillWritesErrorLog() {
        Inquiry savedInquiry = inquiryRepository.save(Inquiry.create("cust-002", "문의", "환불 요청"));
        seedManualChunk("환불은 영업일 기준 3일 내 처리됩니다.");
        fakeLlmClient.failWith(new RuntimeException("upstream timeout"));

        assertThatThrownBy(() -> inquiryAnalysisService.analyze(savedInquiry.getId()))
                .isInstanceOf(RuntimeException.class);

        assertThat(inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(savedInquiry.getId()))
                .isNotEmpty()
                .first()
                .extracting(log -> log.getAnalysisStatus())
                .isEqualTo(AnalysisStatus.FAILURE);
    }

    @Test
    void analyzeRejectsClosedInquiry() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-003", "문의", "멤버십 사용 문의"));
        saved.confirmReview("답변", null, "mimi");
        saved.close();
        inquiryRepository.save(saved);

        assertThatThrownBy(() -> inquiryAnalysisService.analyze(saved.getId()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void agentRunsOutsideTransaction() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-tx", "문의", "환불 문의"));
        seedManualChunk("환불은 영업일 기준 3일 내 처리됩니다.");
        fakeLlmClient.enqueue("""
                {"thought":"바로 답변합니다.","finalAnswer":"안녕하세요. 환불 규정에 따라 ...","category":"REFUND","urgency":"LOW","needsHumanReview":true,"needsEscalation":false,"fraudRiskFlag":false,"reason":"환불 문의"}
                """);

        inquiryAnalysisService.analyze(saved.getId());

        assertThat(fakeLlmClient.transactionActiveDuringCall)
                .as("에이전트가 LLM 을 호출하는 동안 DB 커넥션을 잡고 있으면 안 된다 — 2026-09-04 사고의 원인")
                .isFalse();
    }

    @Test
    void rejectsPersistWhenInquiryClosedDuringAgentRun() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-race", "문의", "환불 문의"));
        seedManualChunk("환불은 영업일 기준 3일 내 처리됩니다.");
        fakeLlmClient.enqueue("""
                {"thought":"바로 답변합니다.","finalAnswer":"안녕하세요. 환불 규정에 따라 ...","category":"REFUND","urgency":"MEDIUM","needsHumanReview":true,"needsEscalation":false,"fraudRiskFlag":false,"reason":"환불 문의"}
                """);
        // 에이전트가 응답을 돌려주기 직전에 상담사가 문의를 종료한다.
        // 트랜잭션을 나눈 뒤 새로 생긴 창이다 — 단일 트랜잭션은 이 보호를 공짜로 주고 있었다.
        fakeLlmClient.beforeResponse = () -> {
            Inquiry concurrent = inquiryRepository.findById(saved.getId()).orElseThrow();
            concurrent.confirmReview("상담사가 먼저 답변했습니다", null, "mimi");
            concurrent.close();
            inquiryRepository.save(concurrent);
        };

        assertThatThrownBy(() -> inquiryAnalysisService.analyze(saved.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("분석 중 문의 상태가 변경");

        assertThat(inquiryRepository.findById(saved.getId()).orElseThrow().getStatus())
                .as("종료된 문의를 뒤늦은 분석 결과로 덮어쓰지 않는다")
                .isEqualTo(InquiryStatus.CLOSED);
    }

    @Test
    void followUpRoundClosesItsLogRow() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-followup", "문의", "주문이 안 와요"));
        seedManualChunk("배송 문의는 주문번호가 필요합니다.");
        fakeLlmClient.enqueue("""
                {"thought":"주문번호가 필요합니다.","followUpQuestion":"주문번호를 알려주시겠어요?"}
                """);

        inquiryAnalysisService.analyze(saved.getId());

        assertThat(inquiryRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(InquiryStatus.PENDING_CUSTOMER);

        List<InquiryAnalysisLog> logs =
                inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(saved.getId());
        assertThat(logs)
                .as("추가 질문 라운드도 로그를 마감해야 한다 — 안 하면 RUNNING 이 영구히 남아 스위퍼가 계속 재분석한다")
                .hasSize(1);
        assertThat(logs.get(0).getAnalysisStatus()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(logs.get(0).getGeneratedDraft()).contains("주문번호");
        assertThat(logs.get(0).getClassifiedCategory())
                .as("추가 질문 단계에는 분류가 없다")
                .isNull();
    }

    @Test
    void successFlipsRunningRowInPlace() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-run", "문의", "환불 문의"));
        seedManualChunk("환불은 영업일 기준 3일 내 처리됩니다.");
        fakeLlmClient.enqueue("""
                {"thought":"바로 답변합니다.","finalAnswer":"안녕하세요. 환불 규정에 따라 ...","category":"REFUND","urgency":"MEDIUM","needsHumanReview":true,"needsEscalation":false,"fraudRiskFlag":false,"reason":"환불 문의"}
                """);

        inquiryAnalysisService.analyze(saved.getId());

        List<InquiryAnalysisLog> logs =
                inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(saved.getId());
        assertThat(logs)
                .as("시도당 로그 행은 하나여야 한다 — RUNNING 행을 제자리에서 뒤집는다")
                .hasSize(1);
        assertThat(logs.get(0).getAnalysisStatus()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(logs.get(0).getLatencyMs()).isNotNull();
    }

    @Test
    void agentFailureFlipsRunningRowToFailureAndLeavesInquiryNew() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-fail2", "문의", "환불 요청"));
        seedManualChunk("환불은 영업일 기준 3일 내 처리됩니다.");
        fakeLlmClient.failWith(new RuntimeException("upstream timeout"));

        assertThatThrownBy(() -> inquiryAnalysisService.analyze(saved.getId()))
                .isInstanceOf(ApiException.class);

        List<InquiryAnalysisLog> logs =
                inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(saved.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILURE);
        assertThat(logs.get(0).getErrorMessage()).contains("upstream timeout");

        assertThat(inquiryRepository.findById(saved.getId()).orElseThrow().getStatus())
                .as("실패한 분석은 문의를 NEW 로 남긴다 — 스위퍼가 로그를 보고 줍는다")
                .isEqualTo(InquiryStatus.NEW);
    }

    @Test
    void rejectsWhenAnalysisAlreadyRunning() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-dup", "문의", "환불 문의"));
        insertRunningLog(saved.getId(), LocalDateTime.now());

        assertThatThrownBy(() -> inquiryAnalysisService.analyze(saved.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이미 분석이 진행 중입니다");
    }

    @Test
    void closesStaleRunningLogBeforeStartingNewAttempt() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-stale", "문의", "환불 문의"));
        seedManualChunk("환불은 영업일 기준 3일 내 처리됩니다.");
        insertRunningLog(saved.getId(), LocalDateTime.now().minusHours(1));
        fakeLlmClient.enqueue("""
                {"thought":"바로 답변합니다.","finalAnswer":"안녕하세요. 환불 규정에 따라 ...","category":"REFUND","urgency":"MEDIUM","needsHumanReview":true,"needsEscalation":false,"fraudRiskFlag":false,"reason":"환불 문의"}
                """);

        inquiryAnalysisService.analyze(saved.getId());

        List<InquiryAnalysisLog> logs =
                inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(saved.getId());
        assertThat(logs).hasSize(2);
        assertThat(logs)
                .as("stale RUNNING 은 FAILURE 로 마감돼야 한다 — 그래야 재시도 상한이 버려진 시도를 센다")
                .extracting(InquiryAnalysisLog::getAnalysisStatus)
                .containsExactlyInAnyOrder(AnalysisStatus.SUCCESS, AnalysisStatus.FAILURE);
        assertThat(logs)
                .noneMatch(entry -> entry.getAnalysisStatus() == AnalysisStatus.RUNNING);
    }

    private void insertRunningLog(Long inquiryId, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                insert into inquiry_analysis_log (
                    inquiry_id, request_snapshot, model_name, prompt_version, analysis_status, created_at
                ) values (?, ?, ?, ?, ?, ?)
                """,
                inquiryId,
                "문의 본문 스냅샷",
                "fake-model",
                "test",
                "RUNNING",
                createdAt
        );
    }

    private void seedManualChunk(String content) {
        jdbcTemplate.update("""
                insert into manual_document (id, title, category, content, version, active, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                1L,
                "환불 안내",
                "REFUND",
                "환불 안내문",
                1,
                true,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        jdbcTemplate.update("""
                insert into manual_chunk (
                    id, manual_document_id, chunk_index, document_version, content, token_count, embedding, active, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                1L,
                1L,
                0,
                1,
                content,
                8,
                null,
                true,
                LocalDateTime.now()
        );
    }

    @TestConfiguration
    static class FakeAiConfig {

        @Bean
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }

        @Bean
        @Primary
        LlmClient llmClient(FakeLlmClient fakeLlmClient) {
            return fakeLlmClient;
        }

        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            return text -> List.of(0.1, 0.2, 0.3);
        }

        @Bean
        @Primary
        com.aicsassistant.analysis.application.CounselorNotificationService counselorNotificationService() {
            return new com.aicsassistant.analysis.application.CounselorNotificationService() {
                @Override
                public void notifyHumanReviewRequired(com.aicsassistant.inquiry.domain.Inquiry inquiry, String reason) {}
                @Override
                public void notifyEscalationRequired(com.aicsassistant.inquiry.domain.Inquiry inquiry, String reason) {}
            };
        }
    }

    static class FakeLlmClient implements LlmClient {

        private final Queue<String> responses = new ArrayDeque<>();
        private RuntimeException failure;

        /** 에이전트가 LLM 을 호출한 시점에 DB 트랜잭션이 열려 있었는지. 미호출이면 null. */
        volatile Boolean transactionActiveDuringCall;

        /** LLM 응답을 돌려주기 직전에 실행할 훅. 에이전트 실행 중 DB 상태가 바뀌는 상황을 만든다. */
        volatile Runnable beforeResponse;

        void enqueue(String response) {
            responses.add(response);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void reset() {
            responses.clear();
            failure = null;
            transactionActiveDuringCall = null;
            beforeResponse = null;
        }

        @Override
        public String complete(String prompt) {
            return complete(List.of());
        }

        @Override
        public String complete(List<ChatMessage> messages) {
            transactionActiveDuringCall = TransactionSynchronizationManager.isActualTransactionActive();
            if (beforeResponse != null) {
                beforeResponse.run();
            }
            if (failure != null) {
                throw failure;
            }
            String next = responses.poll();
            if (next == null) {
                throw new IllegalStateException("No fake LLM response configured");
            }
            return next;
        }

        @Override
        public com.aicsassistant.analysis.infra.llm.LlmResponse completeWithUsage(List<ChatMessage> messages) {
            return new com.aicsassistant.analysis.infra.llm.LlmResponse(complete(messages), 10, 20, 0);
        }

        @Override
        public String modelName() {
            return "fake-llm";
        }
    }
}
