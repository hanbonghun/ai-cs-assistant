package com.aicsassistant.manual.api;

import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.manual.dto.CreateManualDocumentRequest;
import com.aicsassistant.manual.dto.ManualChunkResponse;
import com.aicsassistant.manual.dto.ManualDocumentResponse;
import com.aicsassistant.manual.dto.UpdateManualDocumentRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manual-documents")
public class ManualController {

    private final ManualService manualService;

    public ManualController(ManualService manualService) {
        this.manualService = manualService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ManualDocumentResponse create(@RequestBody CreateManualDocumentRequest request) {
        return manualService.create(request);
    }

    @GetMapping
    public List<ManualDocumentResponse> getAll() {
        return manualService.getAll();
    }

    @GetMapping("/{id}")
    public ManualDocumentResponse get(@PathVariable Long id) {
        return manualService.get(id);
    }

    @PutMapping("/{id}")
    public ManualDocumentResponse update(@PathVariable Long id, @RequestBody UpdateManualDocumentRequest request) {
        return manualService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        manualService.delete(id);
    }

    @GetMapping("/{id}/chunks")
    public List<ManualChunkResponse> getChunks(@PathVariable Long id) {
        return manualService.getChunks(id);
    }
}
