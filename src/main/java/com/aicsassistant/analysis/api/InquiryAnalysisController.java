package com.aicsassistant.analysis.api;

import com.aicsassistant.analysis.application.AnalysisLogService;
import com.aicsassistant.analysis.application.InquiryAnalysisService;
import com.aicsassistant.analysis.dto.InquiryAnalysisLogResponse;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import com.aicsassistant.analysis.dto.RateDraftRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** 최근 분석 로그 5건. 문의 상세({@code GET /api/inquiries/{id}})와 분리해 inquiry → analysis 의존을 끊는다. */
    @GetMapping("/{id}/analysis-logs")
    public List<InquiryAnalysisLogResponse> getAnalysisLogs(@PathVariable Long id) {
        return analysisLogService.getRecentLogs(id);
    }

    @PostMapping("/{id}/rate-draft")
    public void rateDraft(@PathVariable Long id, @Valid @RequestBody RateDraftRequest request) {
        analysisLogService.rateLatestLog(id, request.rating(), request.reason(), request.note());
    }
}
