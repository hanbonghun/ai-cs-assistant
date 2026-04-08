package com.aicsassistant.manual.infra;

import com.aicsassistant.manual.domain.ManualDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManualDocumentRepository extends JpaRepository<ManualDocument, Long> {
}
