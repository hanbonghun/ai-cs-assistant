package com.aicsassistant.manual.application;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.manual.domain.ManualDocument;
import com.aicsassistant.manual.dto.CreateManualDocumentRequest;
import com.aicsassistant.manual.dto.ManualChunkResponse;
import com.aicsassistant.manual.dto.ManualDocumentResponse;
import com.aicsassistant.manual.dto.UpdateManualDocumentRequest;
import com.aicsassistant.manual.infra.ManualChunkJdbcRepository;
import com.aicsassistant.manual.infra.ManualDocumentRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ManualService {

    private final ManualDocumentRepository manualDocumentRepository;
    private final ManualChunkJdbcRepository manualChunkJdbcRepository;
    private final ManualChunker manualChunker;

    public ManualService(
            ManualDocumentRepository manualDocumentRepository,
            ManualChunkJdbcRepository manualChunkJdbcRepository,
            ManualChunker manualChunker
    ) {
        this.manualDocumentRepository = manualDocumentRepository;
        this.manualChunkJdbcRepository = manualChunkJdbcRepository;
        this.manualChunker = manualChunker;
    }

    @Transactional
    public ManualDocumentResponse create(CreateManualDocumentRequest request) {
        ManualDocument document = ManualDocument.create(
                request.title(),
                request.category(),
                request.content()
        );

        ManualDocument saved = manualDocumentRepository.save(document);
        manualChunkJdbcRepository.replaceActiveChunks(saved.getId(), saved.getVersion(), manualChunker.chunk(saved.getContent()));
        return toResponse(saved);
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

    @Transactional
    public ManualDocumentResponse update(Long id, UpdateManualDocumentRequest request) {
        ManualDocument document = getActiveDocument(id);
        document.update(request.title(), request.category(), request.content());

        ManualDocument saved = manualDocumentRepository.save(document);
        manualChunkJdbcRepository.replaceActiveChunks(saved.getId(), saved.getVersion(), manualChunker.chunk(saved.getContent()));
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        ManualDocument document = getActiveDocument(id);
        document.deactivate();
        manualDocumentRepository.save(document);
        manualChunkJdbcRepository.deactivateManual(id);
    }

    public List<ManualChunkResponse> getChunks(Long id) {
        getActiveDocument(id);
        return manualChunkJdbcRepository.findActiveChunksByDocumentId(id);
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
