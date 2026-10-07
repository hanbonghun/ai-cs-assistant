package com.aicsassistant.inquiry.application;

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
import com.aicsassistant.inquiry.dto.InquiryMessageResponse;
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
        return InquiryDetailResponse.from(saved);
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
        return InquiryDetailResponse.from(getInquiryEntity(id));
    }

    /** 종료 가능 여부는 {@link Inquiry#close()} 가 판단한다 — 여기서 다시 검사하지 않는다. */
    @Transactional
    public void close(Long id) {
        getInquiryEntity(id).close();
    }

    public List<InquiryMessageResponse> getMessages(Long inquiryId) {
        return inquiryMessageRepository.findByInquiryIdOrderByCreatedAtAsc(inquiryId)
                .stream()
                .map(InquiryMessageResponse::from)
                .toList();
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
        inquiryMessageRepository.save(getInquiryEntity(id).replyAsCustomer(content));
        eventPublisher.publishEvent(new CustomerReplyEvent(id));
    }

    /**
     * 분석을 시작할 문의. 이미 처리가 끝난 문의는 거부한다.
     *
     * <p>엔티티를 돌려주는 이유: 에이전트가 트랜잭션 밖에서 읽기만 한다(ADR 0009). 상태는 아래
     * {@code record*} 메서드로만 바꾼다.
     */
    public Inquiry getForAnalysis(Long id) {
        Inquiry inquiry = getInquiryEntity(id);
        if (inquiry.getStatus().isFinished()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_STATE",
                    "이미 처리 완료된 문의입니다.");
        }
        return inquiry;
    }

    /** 에이전트 최종 답변을 반영하고 고객 스레드에 AI 메시지로 남긴다. */
    @Transactional
    public Inquiry recordAgentAnswer(
            Long id,
            InquiryCategory category,
            UrgencyLevel urgency,
            String answer,
            boolean needsHuman
    ) {
        Inquiry inquiry = loadForAnalysisResult(id);
        inquiry.applyAgentAnswer(category, urgency, answer, needsHuman);
        inquiryMessageRepository.save(InquiryMessage.of(id, InquiryMessageRole.AI, answer));
        return inquiry;
    }

    /** 에이전트의 추가 질문을 남기고 고객 답변을 기다린다. */
    @Transactional
    public Inquiry recordFollowUpQuestion(Long id, String question) {
        Inquiry inquiry = loadForAnalysisResult(id);
        inquiry.askFollowUp();
        inquiryMessageRepository.save(InquiryMessage.of(id, InquiryMessageRole.AI, question));
        return inquiry;
    }

    /**
     * AI 가 답을 내지 못한 문의를 상담사 검토로 올린다 (ADR 0006).
     *
     * <p>브리핑은 상담사용이라 고객 스레드에 메시지로 남기지 않는다.
     */
    @Transactional
    public Inquiry escalateToCounselor(Long id, String briefing) {
        Inquiry inquiry = loadForAnalysisResult(id);
        inquiry.applyAnalysis(InquiryCategory.GENERAL, UrgencyLevel.MEDIUM, briefing);
        return inquiry;
    }

    /** 처리 결과를 고객 스레드에 AI 메시지로 알린다. */
    @Transactional
    public void notifyCustomer(Long id, String content) {
        getInquiryEntity(id);
        inquiryMessageRepository.save(InquiryMessage.of(id, InquiryMessageRole.AI, content));
    }

    public String getCustomerIdentifier(Long id) {
        return getInquiryEntity(id).getCustomerIdentifier();
    }

    /**
     * 에이전트 실행 중(십수 초) 상담사가 문의를 종료했을 수 있다. 분석 시작과 결과 기록이 다른
     * 트랜잭션이라 결과를 쓰기 직전에 다시 확인한다 (ADR 0009).
     */
    private Inquiry loadForAnalysisResult(Long id) {
        Inquiry inquiry = getInquiryEntity(id);
        if (inquiry.getStatus().isFinished()) {
            throw new ApiException(HttpStatus.CONFLICT, "INQUIRY_STATE_CHANGED",
                    "분석 중 문의 상태가 변경되어 결과를 저장하지 않았습니다.");
        }
        return inquiry;
    }

    private Inquiry getInquiryEntity(Long id) {
        return inquiryRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Inquiry not found"));
    }
}
