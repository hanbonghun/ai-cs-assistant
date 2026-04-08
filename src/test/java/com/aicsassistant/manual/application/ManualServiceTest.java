package com.aicsassistant.manual.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.manual.domain.ManualDocument;
import com.aicsassistant.manual.dto.CreateManualDocumentRequest;
import com.aicsassistant.manual.dto.ManualChunkResponse;
import com.aicsassistant.manual.dto.ManualDocumentResponse;
import com.aicsassistant.manual.dto.UpdateManualDocumentRequest;
import com.aicsassistant.manual.infra.ManualChunkJdbcRepository;
import com.aicsassistant.manual.infra.ManualDocumentRepository;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ManualServiceTest extends PostgresVectorIntegrationTest {

    @Autowired
    ManualService manualService;

    @Autowired
    ManualDocumentRepository manualDocumentRepository;

    @Autowired
    ManualChunkJdbcRepository manualChunkJdbcRepository;

    @Test
    void manualDocumentProvidesExplicitLifecycleMethods() {
        ManualDocument document = ManualDocument.create(
                "예약 변경 안내",
                InquiryCategory.RESERVATION_CHANGE,
                "초기 내용"
        );

        document.update("예약 변경 최신 안내", InquiryCategory.RESERVATION_CHANGE, "수정 내용");
        document.deactivate();

        assertThat(document.getTitle()).isEqualTo("예약 변경 최신 안내");
        assertThat(document.getContent()).isEqualTo("수정 내용");
        assertThat(document.getVersion()).isEqualTo(2);
        assertThat(document.isActive()).isFalse();
    }

    @Test
    void updatingManualIncrementsVersionAndReplacesActiveChunks() {
        Long documentId = manualService.create(
                new CreateManualDocumentRequest("예약 변경 안내", InquiryCategory.RESERVATION_CHANGE, "A".repeat(1200))
        ).id();
        List<ManualChunkResponse> chunksBeforeUpdate = manualService.getChunks(documentId);

        ManualDocumentResponse updated = manualService.update(
                documentId,
                new UpdateManualDocumentRequest("예약 변경 최신 안내", InquiryCategory.RESERVATION_CHANGE, "B".repeat(700))
        );
        List<ManualChunkResponse> activeChunksAfterUpdate = manualChunkJdbcRepository.findActiveChunks().stream()
                .filter(chunk -> chunk.manualDocumentId().equals(documentId))
                .toList();

        assertThat(updated.version()).isEqualTo(2);
        assertThat(activeChunksAfterUpdate)
                .isNotEmpty()
                .allSatisfy(chunk -> {
                    assertThat(chunk.manualDocumentId()).isEqualTo(documentId);
                    assertThat(chunk.documentVersion()).isEqualTo(2);
                });
        assertThat(activeChunksAfterUpdate)
                .extracting(ManualChunkResponse::id)
                .doesNotContainAnyElementsOf(chunksBeforeUpdate.stream().map(ManualChunkResponse::id).toList());
    }

    @Test
    void deactivatingManualMarksDocumentInactiveAndExcludesChunksFromActiveRetrieval() {
        String content = "A".repeat(1200);
        Long documentId = manualService.create(new CreateManualDocumentRequest("환불 안내", InquiryCategory.REFUND, content))
                .id();

        List<ManualChunkResponse> activeChunksBeforeDeactivate = manualService.getChunks(documentId);

        manualService.delete(documentId);

        assertThat(manualDocumentRepository.findById(documentId))
                .isPresent()
                .get()
                .extracting(manualDocument -> manualDocument.isActive())
                .isEqualTo(false);
        assertThat(manualChunkJdbcRepository.findActiveChunks().stream()
                .filter(chunk -> chunk.manualDocumentId().equals(documentId))
                .toList()).isEmpty();
        assertThat(activeChunksBeforeDeactivate).isNotEmpty();
    }
}
