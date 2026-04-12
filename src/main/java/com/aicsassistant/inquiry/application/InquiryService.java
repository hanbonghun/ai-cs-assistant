package com.aicsassistant.inquiry.application;

import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryStatus;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.dto.CreateInquiryRequest;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import com.aicsassistant.inquiry.dto.InquiryListResponse;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class InquiryService {

    private final InquiryRepository inquiryRepository;
    private final InquiryAnalysisLogRepository inquiryAnalysisLogRepository;
    private final InquiryMessageRepository inquiryMessageRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public InquiryDetailResponse create(CreateInquiryRequest request) {
        Inquiry inquiry = Inquiry.create(
                request.customerIdentifier(),
                request.title(),
                request.content(),
                request.category(),
                request.urgency(),
                request.relatedOrderId()
        );
        Inquiry saved = inquiryRepository.save(inquiry);
        eventPublisher.publishEvent(new InquiryCreatedEvent(saved.getId()));
        return InquiryDetailResponse.from(saved, List.of());
    }

    public List<InquiryListResponse> getInquiriesByCustomer(String customerIdentifier) {
        return inquiryRepository.findByCustomerIdentifierOrderByCreatedAtDesc(customerIdentifier)
                .stream()
                .map(InquiryListResponse::from)
                .toList();
    }

    public List<InquiryListResponse> getInquiries(
            InquiryStatus status,
            InquiryCategory category,
            UrgencyLevel urgency
    ) {
        return inquiryRepository.findByFilters(status, category, urgency)
                .stream()
                .map(InquiryListResponse::from)
                .toList();
    }

    public InquiryDetailResponse getInquiry(Long id) {
        Inquiry inquiry = getInquiryEntity(id);
        List<InquiryAnalysisLog> logs = inquiryAnalysisLogRepository
                .findByInquiryIdOrderByCreatedAtDesc(id)
                .stream()
                .limit(5)
                .toList();
        return InquiryDetailResponse.from(inquiry, logs);
    }

    @Transactional
    public void close(Long id) {
        Inquiry inquiry = getInquiryEntity(id);
        if (inquiry.getStatus() != InquiryStatus.REVIEWED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE", "Only REVIEWED inquiries can be closed");
        }
        inquiry.close();
    }

    public List<InquiryMessage> getMessages(Long inquiryId) {
        return inquiryMessageRepository.findByInquiryIdOrderByCreatedAtAsc(inquiryId);
    }

    private Inquiry getInquiryEntity(Long id) {
        return inquiryRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Inquiry not found"));
    }
}
