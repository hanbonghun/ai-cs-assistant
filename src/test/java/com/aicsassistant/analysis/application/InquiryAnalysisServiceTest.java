package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.analysis.domain.AnalysisStatus;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
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
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    void analyzeUpdatesInquiryAndWritesLog() {
        Inquiry savedInquiry = inquiryRepository.save(Inquiry.create("cust-001", "문의", "멤버십 환불이 가능한가요?"));
        seedManualChunk("환불은 영업일 기준 3일 내 처리됩니다.");

        fakeLlmClient.enqueue("""
                {"category":"REFUND","urgency":"MEDIUM","reason":"refund request","needsHumanReview":true,"needsEscalation":false,"medicalRiskFlag":false}
                """);
        fakeLlmClient.enqueue("""
                {"answer":"안녕하세요. 환불 규정에 따라 ...","internalNote":"정책 근거 확인 완료","usedChunkIds":[1,2]}
                """);

        InquiryAnalysisResponse response = inquiryAnalysisService.analyze(savedInquiry.getId());

        assertThat(response.category().value()).isEqualTo(InquiryCategory.REFUND.name());
        assertThat(response.category().reason()).isEqualTo("refund request");
        assertThat(response.category().needsHumanReview()).isTrue();
        assertThat(response.draft().usedChunkIds()).containsExactly(1L, 2L);
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
    }

    static class FakeLlmClient implements LlmClient {

        private final Queue<String> responses = new ArrayDeque<>();
        private RuntimeException failure;

        void enqueue(String response) {
            responses.add(response);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void reset() {
            responses.clear();
            failure = null;
        }

        @Override
        public String complete(String prompt) {
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
        public String modelName() {
            return "fake-llm";
        }
    }
}
