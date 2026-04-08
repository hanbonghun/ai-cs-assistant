package com.aicsassistant.common.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.manual.infra.ManualDocumentRepository;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class LocalSeedDataInitializerTest extends PostgresVectorIntegrationTest {

    @Autowired
    InquiryRepository inquiryRepository;

    @Autowired
    ManualDocumentRepository manualDocumentRepository;

    @Test
    void insertsDemoRecords() {
        assertThat(inquiryRepository.count()).isGreaterThanOrEqualTo(8);
        assertThat(manualDocumentRepository.count()).isGreaterThanOrEqualTo(5);
    }
}
