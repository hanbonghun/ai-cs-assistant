package com.aicsassistant.inquiry.application;

import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
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

    /**
     * 고객이 AI의 추가 질문에 답변을 다는 유스케이스.
     *
     * <p>상태 검증 + 메시지 저장 후 {@link CustomerReplyEvent}를 발행한다. 이어지는 agent
     * 재실행은 트랜잭션 커밋 후 별도 비동기 스레드에서 일어나므로, HTTP 요청은 메시지 저장 직후
     * 즉시 반환되어 LLM 호출 시간만큼 HTTP 커넥션을 잡고 있지 않는다.
     */
    @Transactional
    public void replyAsCustomer(Long id, String content) {
        Inquiry inquiry = getInquiryEntity(id);
        if (inquiry.getStatus() != InquiryStatus.PENDING_CUSTOMER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE",
                    "고객 답변은 PENDING_CUSTOMER 상태에서만 가능합니다.");
        }
        if (content == null || content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_CONTENT", "답변 내용을 입력해 주세요.");
        }
        inquiryMessageRepository.save(InquiryMessage.of(id, InquiryMessageRole.CUSTOMER, content.strip()));
        eventPublisher.publishEvent(new CustomerReplyEvent(id));
    }

    private Inquiry getInquiryEntity(Long id) {
        return inquiryRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Inquiry not found"));
    }
}
