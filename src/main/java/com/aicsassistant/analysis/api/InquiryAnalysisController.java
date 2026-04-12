package com.aicsassistant.analysis.api;

import com.aicsassistant.analysis.application.AnalysisLogService;
import com.aicsassistant.analysis.application.InquiryAnalysisService;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inquiries")
@RequiredArgsConstructor
public class InquiryAnalysisController {

    private final InquiryAnalysisService inquiryAnalysisService;
    private final AnalysisLogService analysisLogService;

    @PostMapping("/{id}/analyze")
    public InquiryAnalysisResponse analyze(@PathVariable Long id) {
        return inquiryAnalysisService.analyze(id);
    }

    @PostMapping("/{id}/rate-draft")
    public ResponseEntity<Void> rateDraft(@PathVariable Long id, @RequestBody RateDraftRequest request) {
        analysisLogService.rateLatestLog(id, request.rating());
        return ResponseEntity.ok().build();
    }

    record RateDraftRequest(String rating) {}
}
