package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.dto.InquiryAnalysisLogResponse;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AnalysisLogServiceTest extends PostgresVectorIntegrationTest {

    @Autowired
    AnalysisLogService analysisLogService;

    @Autowired
    InquiryRepository inquiryRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void returnsRecentAnalysisLogs() {
        Inquiry inquiry = inquiryRepository.save(Inquiry.create("cust-010", "환불 문의", "환불 가능한가요?"));
        jdbcTemplate.update("""
                insert into inquiry_analysis_log (
                    inquiry_id, request_snapshot, classified_category, classified_urgency, retrieved_chunk_ids,
                    generated_draft, model_name, prompt_version, analysis_status, error_message, latency_ms, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                inquiry.getId(),
                "환불 가능한가요?",
                "REFUND",
                "HIGH",
                "1,2,3",
                "초안 답변",
                "gpt-test",
                "v1",
                "SUCCESS",
                null,
                321L
        );

        List<InquiryAnalysisLogResponse> logs = analysisLogService.getRecentLogs(inquiry.getId());

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).generatedDraft()).isEqualTo("초안 답변");
    }
}
