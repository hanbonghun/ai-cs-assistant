package com.aicsassistant.inquiry.application;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.dto.ReviewInquiryRequest;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReviewService {

    private final InquiryRepository inquiryRepository;

    public ReviewService(InquiryRepository inquiryRepository) {
        this.inquiryRepository = inquiryRepository;
    }

    @Transactional
    public void confirm(Long inquiryId, ReviewInquiryRequest request) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Inquiry not found"));
        inquiry.confirmReview(request.finalAnswer(), request.reviewMemo(), request.confirmedBy());
    }
}
