package com.aicsassistant.manual.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.infra.llm.EmbeddingClient;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.manual.domain.ManualDocument;
import com.aicsassistant.manual.dto.CreateManualDocumentRequest;
import com.aicsassistant.manual.dto.ManualChunkResponse;
import com.aicsassistant.manual.dto.ManualDocumentResponse;
import com.aicsassistant.manual.dto.UpdateManualDocumentRequest;
import com.aicsassistant.manual.infra.ManualChunkJdbcRepository;
import com.aicsassistant.manual.infra.ManualDocumentRepository;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@Import(ManualServiceTest.RecordingEmbeddingConfig.class)
class ManualServiceTest extends PostgresVectorIntegrationTest {

    @Autowired
    ManualService manualService;

    @Autowired
    RecordingEmbeddingClient embeddingClient;

    @BeforeEach
    void resetEmbeddingRecorder() {
        embeddingClient.reset();
    }

    @Autowired
    ManualDocumentRepository manualDocumentRepository;

    @Autowired
    ManualChunkJdbcRepository manualChunkJdbcRepository;

    @Test
    void manualDocumentProvidesExplicitLifecycleMethods() {
        ManualDocument document = ManualDocument.create(
                "주문 처리 정책",
                InquiryCategory.ORDER,
                "초기 내용"
        );

        document.update("주문 처리 정책 최신", InquiryCategory.ORDER, "수정 내용");
        document.deactivate();

        assertThat(document.getTitle()).isEqualTo("주문 처리 정책 최신");
        assertThat(document.getContent()).isEqualTo("수정 내용");
        assertThat(document.getVersion()).isEqualTo(2);
        assertThat(document.isActive()).isFalse();
    }

    @Test
    void updatingManualIncrementsVersionAndReplacesActiveChunks() {
        Long documentId = manualService.create(
                new CreateManualDocumentRequest("주문 처리 정책", InquiryCategory.ORDER, "A".repeat(1200))
        ).id();
        List<ManualChunkResponse> chunksBeforeUpdate = manualService.getChunks(documentId);

        ManualDocumentResponse updated = manualService.update(
                documentId,
                new UpdateManualDocumentRequest("주문 처리 정책 최신", InquiryCategory.ORDER, "B".repeat(700))
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

    @Test
    @DisplayName("임베딩은 트랜잭션 밖에서, 청크 수와 무관하게 한 번의 묶음 호출로 돈다")
    void embedsOutsideTransactionInOneBatchedCall() {
        // ManualChunker(500, 100) → 1200자는 청크 3개
        manualService.create(new CreateManualDocumentRequest(
                "환불 정책", InquiryCategory.REFUND, "가".repeat(1200)));

        assertThat(embeddingClient.lastBatchSize)
                .as("청크가 여러 개여야 묶음의 의미가 있다")
                .isGreaterThan(1);
        assertThat(embeddingClient.batchCalls)
                .as("청크마다 한 번이 아니라 묶어서 한 번")
                .isEqualTo(1);
        assertThat(embeddingClient.singleCalls)
                .as("묶음이 성공하면 개별 폴백은 돌지 않는다")
                .isZero();
        assertThat(embeddingClient.transactionActiveDuringCall)
                .as("임베딩이 도는 동안 DB 커넥션을 잡고 있으면 안 된다 — ADR 0009")
                .isFalse();
    }

    @TestConfiguration
    static class RecordingEmbeddingConfig {

        @Bean
        @Primary
        RecordingEmbeddingClient embeddingClient() {
            return new RecordingEmbeddingClient();
        }
    }

    /** 호출 방식과 호출 시점의 트랜잭션 상태를 기록하는 페이크. */
    static class RecordingEmbeddingClient implements EmbeddingClient {

        int batchCalls;
        int singleCalls;
        int lastBatchSize;
        Boolean transactionActiveDuringCall;

        @Override
        public List<Double> embed(String text) {
            singleCalls++;
            recordTransactionState();
            return vector();
        }

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            batchCalls++;
            lastBatchSize = texts.size();
            recordTransactionState();
            return texts.stream().map(t -> vector()).toList();
        }

        private void recordTransactionState() {
            if (transactionActiveDuringCall == null) {
                transactionActiveDuringCall = TransactionSynchronizationManager.isActualTransactionActive();
            }
        }

        /** manual_chunk.embedding 이 vector(1536) 이므로 차원을 맞춰야 저장된다. */
        private static List<Double> vector() {
            return Collections.nCopies(1536, 0.01);
        }

        void reset() {
            batchCalls = 0;
            singleCalls = 0;
            lastBatchSize = 0;
            transactionActiveDuringCall = null;
        }
    }
}
