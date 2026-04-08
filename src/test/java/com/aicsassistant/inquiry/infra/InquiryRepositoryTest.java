package com.aicsassistant.inquiry.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class InquiryRepositoryTest extends PostgresVectorIntegrationTest {
    @Autowired
    InquiryRepository inquiryRepository;

    @Test
    void savesInquiryWithNewStatus() {
        Inquiry inquiry = Inquiry.create("cust-001", "예약 변경", "이번 주 토요일로 바꿔주세요.");

        Inquiry saved = inquiryRepository.save(inquiry);

        assertThat(saved.getStatus()).isEqualTo(InquiryStatus.NEW);
    }
}
