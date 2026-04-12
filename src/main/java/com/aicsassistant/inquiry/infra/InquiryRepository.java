package com.aicsassistant.inquiry.infra;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    List<Inquiry> findByCustomerIdentifierOrderByCreatedAtDesc(String customerIdentifier);

    @Query("""
            SELECT i FROM Inquiry i
            WHERE (:status IS NULL OR i.status = :status)
              AND (:category IS NULL OR i.category = :category)
              AND (:urgency IS NULL OR i.urgency = :urgency)
            ORDER BY i.createdAt DESC
            """)
    List<Inquiry> findByFilters(
            @Param("status") InquiryStatus status,
            @Param("category") InquiryCategory category,
            @Param("urgency") UrgencyLevel urgency
    );
}
