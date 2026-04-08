package com.aicsassistant.common.bootstrap;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.manual.dto.CreateManualDocumentRequest;
import com.aicsassistant.manual.infra.ManualDocumentRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Profile("local")
@Component
public class LocalSeedDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSeedDataInitializer.class);

    private final InquiryRepository inquiryRepository;
    private final ManualDocumentRepository manualDocumentRepository;
    private final ManualService manualService;

    public LocalSeedDataInitializer(
            InquiryRepository inquiryRepository,
            ManualDocumentRepository manualDocumentRepository,
            ManualService manualService
    ) {
        this.inquiryRepository = inquiryRepository;
        this.manualDocumentRepository = manualDocumentRepository;
        this.manualService = manualService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (inquiryRepository.count() > 0 || manualDocumentRepository.count() > 0) {
            log.info("Skipping local seed data because demo tables are already populated.");
            return;
        }

        seedInquiries();
        seedManuals();
        log.info("Seeded local demo inquiries and manuals.");
    }

    private void seedInquiries() {
        List<Inquiry> inquiries = List.of(
                Inquiry.create("demo-cust-001", "예약 시간을 변경하고 싶어요", "이번 주 금요일 오후로 예약 시간을 변경할 수 있나요?", InquiryCategory.RESERVATION_CHANGE, UrgencyLevel.MEDIUM),
                Inquiry.create("demo-cust-002", "예약을 취소하고 싶어요", "오늘 예약을 취소하면 위약금이 발생하나요?", InquiryCategory.RESERVATION_CANCEL, UrgencyLevel.HIGH),
                Inquiry.create("demo-cust-003", "환불 규정을 확인하고 싶어요", "시술 후 만족하지 못했을 때 환불 가능한지 알고 싶어요.", InquiryCategory.REFUND, UrgencyLevel.HIGH),
                Inquiry.create("demo-cust-004", "가격 문의", "기본 시술과 추가 옵션 가격이 어떻게 되나요?", InquiryCategory.PRICE, UrgencyLevel.MEDIUM),
                Inquiry.create("demo-cust-005", "시술 후 주의사항", "시술 다음 날 운동을 해도 되는지 궁금합니다.", InquiryCategory.POST_TREATMENT, UrgencyLevel.LOW),
                Inquiry.create("demo-cust-006", "멤버십 혜택", "멤버십 가입 시 적립과 할인 혜택이 어떻게 적용되나요?", InquiryCategory.MEMBERSHIP, UrgencyLevel.MEDIUM),
                Inquiry.create("demo-cust-007", "응대가 불친절했어요", "지난 방문 때 안내가 충분하지 않았고 응대도 불편했습니다.", InquiryCategory.COMPLAINT, UrgencyLevel.HIGH),
                Inquiry.create("demo-cust-008", "일반 문의", "주차 가능한지와 대기 시간이 얼마나 되는지 알고 싶어요.", InquiryCategory.GENERAL, UrgencyLevel.LOW),
                Inquiry.create("demo-cust-009", "예약 변경이 다시 가능할까요?", "이미 한 번 변경했는데 다시 일정 조정이 필요한 상황입니다.", InquiryCategory.RESERVATION_CHANGE, UrgencyLevel.MEDIUM),
                Inquiry.create("demo-cust-010", "환불 절차", "환불 신청 시 어떤 서류가 필요한지 궁금합니다.", InquiryCategory.REFUND, UrgencyLevel.HIGH),
                Inquiry.create("demo-cust-011", "가격 차이가 있나요?", "주중과 주말 가격 차이가 있는지 확인하고 싶어요.", InquiryCategory.PRICE, UrgencyLevel.LOW),
                Inquiry.create("demo-cust-012", "시술 후 관리", "붓기와 통증이 있을 때 어떤 관리를 하면 되나요?", InquiryCategory.POST_TREATMENT, UrgencyLevel.MEDIUM)
        );

        inquiryRepository.saveAll(inquiries);
    }

    private void seedManuals() {
        List<CreateManualDocumentRequest> manuals = List.of(
                new CreateManualDocumentRequest(
                        "예약 변경 안내",
                        InquiryCategory.RESERVATION_CHANGE,
                        manualContent(
                                "예약 변경은 최소 24시간 전까지 가능하며, 당일 변경은 상담실 재배정 상황에 따라 제한될 수 있습니다.",
                                "변경 요청 시 고객 성함, 예약 일시, 희망 일정, 담당자를 함께 확인한 뒤 일정표를 업데이트합니다.",
                                "반복적인 변경 요청이 있는 경우에는 대기 시간을 줄이기 위해 가능한 시간대를 2개 이상 안내합니다."
                        )
                ),
                new CreateManualDocumentRequest(
                        "예약 취소 및 노쇼 안내",
                        InquiryCategory.RESERVATION_CANCEL,
                        manualContent(
                                "예약 취소는 앱, 전화, 카카오 채널 모두에서 접수할 수 있으며, 취소 시각은 반드시 기록합니다.",
                                "당일 취소와 노쇼는 고객 응대 이력에 남기고, 재예약 가능 여부는 운영 정책에 따라 안내합니다.",
                                "위약금이 적용되는 경우에는 적용 기준과 예외 조건을 함께 설명해 오해가 없도록 합니다."
                        )
                ),
                new CreateManualDocumentRequest(
                        "환불 정책 안내",
                        InquiryCategory.REFUND,
                        manualContent(
                                "환불은 결제일, 이용 횟수, 사용한 상품 여부를 기준으로 검토하며, 서면 확인 후 처리합니다.",
                                "시술형 상품은 사용 여부와 경과 일수를 확인하고, 패키지 상품은 잔여 회차를 계산합니다.",
                                "고객이 불만을 제기한 경우에는 즉시 사실관계를 기록하고, 담당 매니저에게 에스컬레이션합니다."
                        )
                ),
                new CreateManualDocumentRequest(
                        "가격 및 결제 안내",
                        InquiryCategory.PRICE,
                        manualContent(
                                "기본 가격은 지점별로 다를 수 있으므로, 상담 전 최신 요금표를 확인한 뒤 안내합니다.",
                                "할인, 패키지, 멤버십 적용 여부를 먼저 확인하고 최종 결제 금액을 설명합니다.",
                                "추가 옵션은 선택 사항임을 분명히 안내하고, 고객이 원하지 않는 항목이 자동 포함되지 않도록 합니다."
                        )
                ),
                new CreateManualDocumentRequest(
                        "시술 후 주의사항",
                        InquiryCategory.POST_TREATMENT,
                        manualContent(
                                "시술 후 24시간 동안은 격한 운동, 사우나, 음주를 피하도록 안내합니다.",
                                "붓기와 열감은 냉찜질과 충분한 휴식으로 관리하고, 통증이 심하면 즉시 내원하도록 설명합니다.",
                                "이상 반응이나 알레르기 의심 증상이 보이면 사진을 요청해 상태를 기록한 뒤 의료진에게 전달합니다."
                        )
                ),
                new CreateManualDocumentRequest(
                        "멤버십 이용 가이드",
                        InquiryCategory.MEMBERSHIP,
                        manualContent(
                                "멤버십 적립은 결제 완료 시점에 반영되며, 할인과 적립 중복 적용 가능 여부를 먼저 확인합니다.",
                                "회원 등급에 따라 사용 가능한 예약 창구와 혜택이 다를 수 있으므로, 등급별 정책을 기준으로 안내합니다.",
                                "탈퇴 또는 명의 변경 요청이 들어오면 잔여 포인트와 쿠폰 처리 방식도 함께 확인합니다."
                        )
                ),
                new CreateManualDocumentRequest(
                        "민원 응대 기준",
                        InquiryCategory.COMPLAINT,
                        manualContent(
                                "불만 접수 시에는 먼저 고객의 불편을 확인하고, 사실관계를 정리한 뒤 감정적 대응을 피합니다.",
                                "응대 기록, 담당자, 시간, 장소를 남기고 필요한 경우 사진이나 메시지 로그를 첨부합니다.",
                                "반복 민원이나 서비스 장애는 지점 책임자와 즉시 공유해 후속 안내가 지연되지 않도록 합니다."
                        )
                ),
                new CreateManualDocumentRequest(
                        "일반 운영 안내",
                        InquiryCategory.GENERAL,
                        manualContent(
                                "주차 가능 여부, 대기 시간, 휴무 일정, 상담 가능 시간은 운영표를 기준으로 안내합니다.",
                                "문의 유형이 불명확하면 가장 가까운 카테고리로 분류한 뒤 담당 부서로 전달합니다.",
                                "외부 일정으로 영업이 변경되는 경우에는 홈페이지와 현장 안내문을 동시에 확인하도록 합니다."
                        )
                )
        );

        for (CreateManualDocumentRequest manual : manuals) {
            manualService.create(manual);
        }
    }

    private String manualContent(String... paragraphs) {
        return String.join("\n\n", paragraphs);
    }
}
