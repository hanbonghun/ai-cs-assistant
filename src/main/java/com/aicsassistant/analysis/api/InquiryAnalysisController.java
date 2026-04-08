package com.aicsassistant.analysis.api;

import com.aicsassistant.analysis.application.InquiryAnalysisService;
import com.aicsassistant.analysis.dto.InquiryAnalysisResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inquiries")
public class InquiryAnalysisController {

    private final InquiryAnalysisService inquiryAnalysisService;

    public InquiryAnalysisController(InquiryAnalysisService inquiryAnalysisService) {
        this.inquiryAnalysisService = inquiryAnalysisService;
    }

    @PostMapping("/{id}/analyze")
    public InquiryAnalysisResponse analyze(@PathVariable Long id) {
        return inquiryAnalysisService.analyze(id);
    }
}
