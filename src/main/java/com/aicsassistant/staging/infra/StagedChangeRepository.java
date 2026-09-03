package com.aicsassistant.staging.infra;

import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.domain.StagedChangeStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StagedChangeRepository extends JpaRepository<StagedChange, Long> {

    boolean existsByOrderIdAndStatus(String orderId, StagedChangeStatus status);

    List<StagedChange> findByInquiryIdOrderByCreatedAtDesc(Long inquiryId);
}
