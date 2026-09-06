package com.aicsassistant.manual.application;

import com.aicsassistant.analysis.infra.llm.EmbeddingClient;
import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.manual.domain.ManualDocument;
import com.aicsassistant.manual.dto.CreateManualDocumentRequest;
import com.aicsassistant.manual.dto.ManualChunkResponse;
import com.aicsassistant.manual.dto.ManualDocumentResponse;
import com.aicsassistant.manual.dto.UpdateManualDocumentRequest;
import com.aicsassistant.manual.infra.ManualChunkJdbcRepository;
import com.aicsassistant.manual.infra.ManualDocumentRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 정책 문서 등록·수정과 색인.
 *
 * <p>색인은 두 구간으로 나뉜다 — <b>청킹·임베딩(트랜잭션 밖)</b> 과 <b>저장(짧은 트랜잭션)</b>.
 * 임베딩은 청크 수만큼 OpenAI 를 호출하므로, 한 트랜잭션에 함께 두면 A4 열 장짜리 문서 하나가
 * DB 커넥션을 수십 초 잡는다. {@code InquiryAnalysisService} 가 같은 이유로 고쳐졌다 — 근거는
 * ADR 0009.
 *
 * <p>임베딩은 요청 본문의 텍스트만 있으면 계산되므로 DB 를 건드리기 전에 끝낼 수 있다.
 * 덕분에 경계를 나눠도 문서와 청크는 여전히 한 커밋에 들어간다.
 *
 * <p>경계를 {@code @Transactional} 로 나누지 않은 이유: 같은 빈 안에서 호출하면 프록시를 타지 않아
 * 트랜잭션이 조용히 걸리지 않는다. 이 클래스의 트랜잭션 구간은 서너 줄이라 별도 빈을 만들 만큼의
 * 무게가 없어 {@link TransactionTemplate} 을 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualService {

    private final ManualDocumentRepository manualDocumentRepository;
    private final ManualChunkJdbcRepository manualChunkJdbcRepository;
    private final ManualChunker manualChunker;
    private final EmbeddingClient embeddingClient;
    private final FileTextExtractor fileTextExtractor;
    private final TransactionTemplate txTemplate;

    public ManualDocumentResponse create(CreateManualDocumentRequest request) {
        List<ChunkWithEmbedding> chunks = embedChunks(manualChunker.chunk(request.content()));

        return txTemplate.execute(status -> {
            ManualDocument saved = manualDocumentRepository.save(ManualDocument.create(
                    request.title(), request.category(), request.content()));
            manualChunkJdbcRepository.replaceActiveChunks(saved.getId(), saved.getVersion(), chunks);
            return toResponse(saved);
        });
    }

    /**
     * 임베딩 전에 문서 존재를 한 번 확인한다 — 없는 문서에 임베딩 비용을 쓰지 않기 위해서다.
     * 저장 구간에서 다시 조회하므로, 그 사이에 삭제돼도 결과가 어긋나지 않는다.
     */
    public ManualDocumentResponse update(Long id, UpdateManualDocumentRequest request) {
        getActiveDocument(id);
        List<ChunkWithEmbedding> chunks = embedChunks(manualChunker.chunk(request.content()));

        return txTemplate.execute(status -> {
            ManualDocument document = getActiveDocument(id);
            document.update(request.title(), request.category(), request.content());

            ManualDocument saved = manualDocumentRepository.save(document);
            manualChunkJdbcRepository.replaceActiveChunks(saved.getId(), saved.getVersion(), chunks);
            return toResponse(saved);
        });
    }

    /** 외부 호출이 없으므로 트랜잭션을 나눌 이유가 없다. */
    @Transactional
    public void delete(Long id) {
        ManualDocument document = getActiveDocument(id);
        document.deactivate();
        manualDocumentRepository.save(document);
        manualChunkJdbcRepository.deactivateManual(id);
    }

    /** 파일 파싱(PDFBox)도 트랜잭션 밖이다. 문서가 클수록 오래 걸린다. */
    public ManualDocumentResponse createFromFile(String title, InquiryCategory category, MultipartFile file) {
        String content = fileTextExtractor.extract(file);
        return create(new CreateManualDocumentRequest(title, category, content));
    }

    public List<ManualDocumentResponse> getAll() {
        return manualDocumentRepository.findAll().stream()
                .filter(ManualDocument::isActive)
                .map(this::toResponse)
                .toList();
    }

    public ManualDocumentResponse get(Long id) {
        return toResponse(getActiveDocument(id));
    }

    public List<ManualChunkResponse> getChunks(Long id) {
        getActiveDocument(id);
        return manualChunkJdbcRepository.findActiveChunksByDocumentId(id);
    }

    public List<com.aicsassistant.ui.viewmodel.InquiryDetailViewModel.EvidenceChunkView> getEvidenceChunks(List<Long> chunkIds) {
        return manualChunkJdbcRepository.findEvidenceChunksByIds(chunkIds);
    }

    // ---------- 색인 (트랜잭션 밖) ----------

    private List<ChunkWithEmbedding> embedChunks(List<String> contents) {
        if (contents.isEmpty()) {
            return List.of();
        }
        List<List<Double>> vectors = embedAllOrPerChunk(contents);

        List<ChunkWithEmbedding> chunks = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            chunks.add(new ChunkWithEmbedding(contents.get(i), vectors.get(i)));
        }
        return chunks;
    }

    /**
     * 묶음 호출이 실패하면 청크별로 다시 시도한다.
     *
     * <p>묶음은 한 청크의 문제로 통째로 실패할 수 있다. 그대로 포기하면 문서 전체가 벡터 없이
     * 저장되어 키워드 검색으로만 걸리는데, 그 열화는 예외 없이 검색 품질로만 드러나 알아채기 어렵다.
     * 한 건씩 다시 돌면 실제로 문제인 청크만 벡터 없이 남는다.
     */
    private List<List<Double>> embedAllOrPerChunk(List<String> contents) {
        try {
            return embeddingClient.embedAll(contents);
        } catch (Exception e) {
            log.warn("임베딩 묶음 호출 실패, 청크 {}건을 개별 재시도합니다: {}", contents.size(), e.getMessage());
            return contents.stream().map(this::embedOrNull).toList();
        }
    }

    private List<Double> embedOrNull(String content) {
        try {
            return embeddingClient.embed(content);
        } catch (Exception e) {
            log.warn("임베딩 생성 실패, null 로 저장합니다: {}", e.getMessage());
            return null;
        }
    }

    private ManualDocument getActiveDocument(Long id) {
        return manualDocumentRepository.findById(id)
                .filter(ManualDocument::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MANUAL_DOCUMENT_NOT_FOUND", "Manual document not found"));
    }

    private ManualDocumentResponse toResponse(ManualDocument document) {
        return new ManualDocumentResponse(
                document.getId(),
                document.getTitle(),
                document.getCategory(),
                document.getContent(),
                document.getVersion(),
                document.isActive(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
