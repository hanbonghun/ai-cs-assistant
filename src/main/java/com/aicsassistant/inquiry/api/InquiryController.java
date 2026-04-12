package com.aicsassistant.inquiry.api;

import com.aicsassistant.inquiry.application.InquiryService;
import com.aicsassistant.inquiry.application.ReviewService;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.dto.CreateInquiryRequest;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import com.aicsassistant.inquiry.dto.InquiryListResponse;
import com.aicsassistant.inquiry.dto.ReviewInquiryRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inquiries")
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService inquiryService;
    private final ReviewService reviewService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InquiryDetailResponse create(@Valid @RequestBody CreateInquiryRequest request) {
        return inquiryService.create(request);
    }

    @GetMapping
    public List<InquiryListResponse> getInquiries(
            @RequestParam(required = false) InquiryStatus status,
            @RequestParam(required = false) InquiryCategory category,
            @RequestParam(required = false) UrgencyLevel urgency
    ) {
        return inquiryService.getInquiries(status, category, urgency);
    }

    @GetMapping("/{id}")
    public InquiryDetailResponse getInquiry(@PathVariable Long id) {
        return inquiryService.getInquiry(id);
    }

    @PostMapping("/{id}/review")
    public void review(@PathVariable Long id, @Valid @RequestBody ReviewInquiryRequest request) {
        reviewService.confirm(id, request);
    }

    @PostMapping("/{id}/close")
    public void close(@PathVariable Long id) {
        inquiryService.close(id);
    }
}
