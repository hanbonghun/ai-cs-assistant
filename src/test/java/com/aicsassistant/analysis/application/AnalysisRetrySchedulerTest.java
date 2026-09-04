package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.domain.AnalysisStatus;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(InquiryAnalysisServiceTest.FakeAiConfig.class)
class AnalysisRetrySchedulerTest extends PostgresVectorIntegrationTest {

    @Autowired
    AnalysisRetryScheduler scheduler;

    @Autowired
    InquiryRepository inquiryRepository;

    @Autowired
    InquiryAnalysisLogRepository logRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    InquiryAnalysisServiceTest.FakeLlmClient fakeLlmClient;

    @BeforeEach
    void setUp() {
        fakeLlmClient.reset();
        jdbcTemplate.update("delete from inquiry_analysis_log");
        jdbcTemplate.update("delete from inquiry_message");
        jdbcTemplate.update("delete from manual_chunk");
        jdbcTemplate.update("delete from manual_document");
        jdbcTemplate.update("delete from inquiry");
    }

    @Test
    void picksUpStaleRunningAndCompletesAnalysis() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-sweep", "문의", "환불 문의"));
        insertLog(saved.getId(), "RUNNING", LocalDateTime.now().minusHours(1), null);
        fakeLlmClient.enqueue("""
                {"thought":"바로 답변합니다.","finalAnswer":"안녕하세요. 환불 규정에 따라 ...","category":"REFUND","urgency":"MEDIUM","needsHumanReview":true,"needsEscalation":false,"fraudRiskFlag":false,"reason":"환불 문의"}
                """);

        scheduler.retryStalledAnalyses();

        assertThat(inquiryRepository.findById(saved.getId()).orElseThrow().getStatus())
                .as("스위퍼가 유실된 RUNNING 을 주워 분석을 끝내야 한다")
                .isEqualTo(InquiryStatus.AI_PROCESSED);
    }

    @Test
    void picksUpLatestFailure() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-retry", "문의", "환불 문의"));
        insertLog(saved.getId(), "FAILURE", LocalDateTime.now().minusMinutes(10), "upstream timeout");
        fakeLlmClient.enqueue("""
                {"thought":"바로 답변합니다.","finalAnswer":"안녕하세요. 환불 규정에 따라 ...","category":"REFUND","urgency":"MEDIUM","needsHumanReview":true,"needsEscalation":false,"fraudRiskFlag":false,"reason":"환불 문의"}
                """);

        scheduler.retryStalledAnalyses();

        assertThat(inquiryRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(InquiryStatus.AI_PROCESSED);
    }

    @Test
    void skipsInquiriesWhoseLatestAnalysisSucceeded() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-ok", "문의", "환불 문의"));
        insertLog(saved.getId(), "FAILURE", LocalDateTime.now().minusMinutes(20), "transient");
        insertLog(saved.getId(), "SUCCESS", LocalDateTime.now().minusMinutes(10), null);

        scheduler.retryStalledAnalyses();

        assertThat(logRepository.countByInquiryIdAndAnalysisStatus(saved.getId(), AnalysisStatus.RUNNING))
                .as("최신 로그가 SUCCESS 면 다시 분석하지 않는다")
                .isZero();
    }

    private void insertLog(Long inquiryId, String status, LocalDateTime createdAt, String errorMessage) {
        jdbcTemplate.update("""
                insert into inquiry_analysis_log (
                    inquiry_id, request_snapshot, model_name, prompt_version,
                    analysis_status, error_message, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                inquiryId, "문의 본문 스냅샷", "fake-model", "test", status, errorMessage, createdAt);
    }
}
