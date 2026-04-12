package com.aicsassistant.inquiry.infra;

import com.aicsassistant.inquiry.domain.InquiryMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryMessageRepository extends JpaRepository<InquiryMessage, Long> {

    List<InquiryMessage> findByInquiryIdOrderByCreatedAtAsc(Long inquiryId);
}
