package com.aicsassistant.staging.api;

import com.aicsassistant.staging.application.StagedChangeApprovalService;
import com.aicsassistant.staging.dto.StagedChangeDecisionRequest;
import com.aicsassistant.staging.dto.StagedChangeResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inquiries/{inquiryId}/staged-changes")
@RequiredArgsConstructor
public class StagedChangeController {

    private final StagedChangeApprovalService approvalService;

    @PostMapping("/{changeId}/approve")
    public StagedChangeResponse approve(
            @PathVariable Long inquiryId,
            @PathVariable Long changeId,
            @Valid @RequestBody StagedChangeDecisionRequest request) {
        return approvalService.approve(inquiryId, changeId, request);
    }

    @PostMapping("/{changeId}/reject")
    public StagedChangeResponse reject(
            @PathVariable Long inquiryId,
            @PathVariable Long changeId,
            @Valid @RequestBody StagedChangeDecisionRequest request) {
        return approvalService.reject(inquiryId, changeId, request);
    }
}
