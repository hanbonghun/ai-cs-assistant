package com.aicsassistant.analysis.infra;

import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryAnalysisLogRepository extends JpaRepository<InquiryAnalysisLog, Long> {
}
