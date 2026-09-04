package com.aicsassistant.analysis.infra;

import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryAnalysisLogRepository extends JpaRepository<InquiryAnalysisLog, Long> {

    List<InquiryAnalysisLog> findByInquiryIdOrderByCreatedAtDesc(Long inquiryId);

    Optional<InquiryAnalysisLog> findFirstByInquiryIdOrderByIdDesc(Long inquiryId);
}
