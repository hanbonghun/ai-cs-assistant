package com.aicsassistant.analysis.infra;

import com.aicsassistant.analysis.domain.AnalysisStatus;
import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryAnalysisLogRepository extends JpaRepository<InquiryAnalysisLog, Long> {

    List<InquiryAnalysisLog> findByInquiryIdOrderByCreatedAtDesc(Long inquiryId);

    Optional<InquiryAnalysisLog> findFirstByInquiryIdOrderByIdDesc(Long inquiryId);

    /**
     * 재시도가 필요한 문의 id. "이 문의의 최신 로그가 FAILURE 이거나, RUNNING 인데 stale" 을 뜻한다.
     *
     * <p>최신 여부를 {@code createdAt} 이 아니라 {@code id} 로 비교한다 — bigserial 이 단조 증가라
     * 같은 타임스탬프로 인한 모호함이 없다.
     */
    @Query("""
            SELECT l.inquiry.id FROM InquiryAnalysisLog l
            WHERE l.inquiry.status IN :inquiryStatuses
              AND (l.analysisStatus = :failure
                   OR (l.analysisStatus = :running AND l.createdAt < :staleCutoff))
              AND NOT EXISTS (
                  SELECT 1 FROM InquiryAnalysisLog newer
                  WHERE newer.inquiry = l.inquiry AND newer.id > l.id
              )
            ORDER BY l.id ASC
            """)
    List<Long> findInquiryIdsNeedingRetry(
            @Param("inquiryStatuses") Collection<InquiryStatus> inquiryStatuses,
            @Param("failure") AnalysisStatus failure,
            @Param("running") AnalysisStatus running,
            @Param("staleCutoff") LocalDateTime staleCutoff
    );

    long countByInquiryIdAndAnalysisStatus(Long inquiryId, AnalysisStatus analysisStatus);
}
