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

        seedManuals();
        seedInquiries();
        log.info("Seeded local demo inquiries and manuals.");
    }

    private void seedInquiries() {
        List<Inquiry> inquiries = List.of(
                // 주문
                Inquiry.create("cust-001", "주문한 상품을 취소하고 싶어요", "어제 오후에 주문했는데 아직 배송 준비 중 상태입니다. 지금 취소가 가능한가요? 주문번호는 ORD-20240411-00123입니다.", InquiryCategory.ORDER, UrgencyLevel.MEDIUM),
                Inquiry.create("cust-002", "주문 수량을 변경할 수 있나요?", "방금 주문했는데 수량을 2개에서 1개로 줄이고 싶습니다. 주문번호 ORD-20240411-00456입니다.", InquiryCategory.ORDER, UrgencyLevel.HIGH),
                // 배송
                Inquiry.create("cust-003", "배송이 너무 늦어요", "5일 전에 주문했는데 아직도 배송 중입니다. 주문번호 ORD-20240406-00789. 내일까지 꼭 받아야 하는데 가능한가요?", InquiryCategory.DELIVERY, UrgencyLevel.HIGH),
                Inquiry.create("cust-004", "다른 주소로 잘못 배송된 것 같아요", "택배 조회하니 이웃집에 배달됐다고 나오는데 저는 받지 못했습니다. 어떻게 해야 하나요?", InquiryCategory.DELIVERY, UrgencyLevel.HIGH),
                Inquiry.create("cust-005", "배송지를 변경하고 싶어요", "주문 시 회사 주소로 입력했는데 집으로 바꿀 수 있을까요? 아직 출고 전인 것 같습니다.", InquiryCategory.DELIVERY, UrgencyLevel.MEDIUM),
                // 반품
                Inquiry.create("cust-006", "상품이 불량입니다", "받은 제품에 스크래치가 있고 포장도 찢겨 있었습니다. 반품 처리 원합니다. 사진 첨부했습니다.", InquiryCategory.RETURN, UrgencyLevel.HIGH),
                Inquiry.create("cust-007", "단순 변심으로 반품하고 싶어요", "구매 후 일주일이 지났는데 반품이 가능한가요? 제품은 미개봉 상태입니다.", InquiryCategory.RETURN, UrgencyLevel.MEDIUM),
                Inquiry.create("cust-008", "반품 택배를 어떻게 보내야 하나요?", "반품 신청했는데 택배사와 주소를 알려주세요. 직접 보내야 하나요?", InquiryCategory.RETURN, UrgencyLevel.LOW),
                // 교환
                Inquiry.create("cust-009", "사이즈 교환 요청", "XL을 주문했는데 L로 교환하고 싶습니다. 재고가 있나요?", InquiryCategory.EXCHANGE, UrgencyLevel.MEDIUM),
                Inquiry.create("cust-010", "색상이 사진과 다릅니다", "홈페이지 사진은 진한 네이비인데 받은 제품은 거의 검정에 가깝습니다. 교환 원합니다.", InquiryCategory.EXCHANGE, UrgencyLevel.MEDIUM),
                // 환불
                Inquiry.create("cust-011", "환불 처리가 너무 오래 걸려요", "반품 접수한 지 7일이 지났는데 환불이 안 됐습니다. 언제 되나요?", InquiryCategory.REFUND, UrgencyLevel.HIGH),
                Inquiry.create("cust-012", "부분 취소 환불 문의", "3개 주문 중 1개만 취소했는데 환불 금액이 맞는지 확인하고 싶습니다.", InquiryCategory.REFUND, UrgencyLevel.MEDIUM),
                Inquiry.create("cust-013", "카드 결제 환불이 언제 되나요?", "환불 승인받았는데 카드사에 언제 반영되는지 알고 싶습니다.", InquiryCategory.REFUND, UrgencyLevel.LOW),
                // 결제
                Inquiry.create("cust-014", "결제가 두 번 됐어요", "같은 주문이 카드에 두 번 청구됐습니다. 확인 부탁드립니다. 매우 급합니다.", InquiryCategory.PAYMENT, UrgencyLevel.HIGH),
                Inquiry.create("cust-015", "무통장 입금 기한이 지났어요", "어제 주문하고 오늘 입금하려고 했는데 주문이 자동 취소됐다고 합니다. 다시 주문해야 하나요?", InquiryCategory.PAYMENT, UrgencyLevel.MEDIUM),
                // 상품
                Inquiry.create("cust-016", "품절 상품 재입고 문의", "상품 코드 PRD-9902 재입고 예정이 있나요? 꼭 필요한 제품입니다.", InquiryCategory.PRODUCT, UrgencyLevel.LOW),
                Inquiry.create("cust-017", "상품 상세 정보 문의", "해당 제품이 식품 알레르기 성분이 포함되어 있는지 알고 싶습니다. 땅콩 알레르기가 있습니다.", InquiryCategory.PRODUCT, UrgencyLevel.HIGH),
                // 회원/혜택
                Inquiry.create("cust-018", "포인트가 사라졌어요", "보유 포인트 15,000점이 갑자기 0원으로 바뀌었습니다. 확인 부탁드립니다.", InquiryCategory.MEMBERSHIP, UrgencyLevel.HIGH),
                Inquiry.create("cust-019", "쿠폰이 적용이 안 돼요", "회원가입 쿠폰 10% 할인을 결제 시 입력했는데 적용이 안 됩니다.", InquiryCategory.MEMBERSHIP, UrgencyLevel.MEDIUM),
                // 불만
                Inquiry.create("cust-020", "상담사 응대가 너무 불친절했습니다", "어제 전화 상담에서 제 말을 끊고 일방적으로 말하더니 전화를 끊었습니다. 정식으로 민원을 접수하고 싶습니다.", InquiryCategory.COMPLAINT, UrgencyLevel.HIGH),
                Inquiry.create("cust-021", "같은 문제로 세 번째 연락입니다", "지난주부터 동일한 문제로 세 번 연락드렸는데 매번 담당자가 다르고 처음부터 설명해야 합니다. 담당자 지정을 요청합니다.", InquiryCategory.COMPLAINT, UrgencyLevel.HIGH),
                // 일반
                Inquiry.create("cust-022", "고객센터 운영 시간 문의", "주말에도 상담이 가능한가요? 평일에 연락하기 어렵습니다.", InquiryCategory.GENERAL, UrgencyLevel.LOW),
                Inquiry.create("cust-023", "기업 구매 담당자입니다", "법인 명의로 대량 구매 시 세금계산서 발행이 가능한지, 별도 단가 협의가 되는지 알고 싶습니다.", InquiryCategory.GENERAL, UrgencyLevel.MEDIUM)
        );

        inquiryRepository.saveAll(inquiries);
    }

    private void seedManuals() {
        List<CreateManualDocumentRequest> manuals = List.of(

                new CreateManualDocumentRequest(
                        "주문 처리 정책",
                        InquiryCategory.ORDER,
                        join(
                                "주문은 결제 완료 시점을 기준으로 접수되며, 주문번호는 'ORD-날짜-순번' 형식으로 자동 부여됩니다. 주문 내역은 마이페이지 > 주문/배송 조회에서 확인할 수 있습니다.",
                                "주문 변경(수량, 옵션, 배송지)은 '결제 완료' 또는 '상품 준비 중' 상태에서만 가능합니다. '배송 준비 중' 이후에는 변경이 불가하며, 이 경우 취소 후 재주문 안내를 합니다.",
                                "주문 취소는 '상품 준비 중' 단계까지 고객이 직접 앱/웹에서 처리할 수 있습니다. '배송 준비 중' 이후에는 고객센터를 통해서만 취소 요청이 가능하며, 이미 출고된 경우 반품 절차로 안내합니다.",
                                "당일 오후 2시 이전 주문은 당일 출고를 원칙으로 합니다. 단, 재고 상황 또는 물류센터 사정에 따라 익일 출고로 변경될 수 있으며, 이 경우 고객에게 SMS로 사전 안내합니다.",
                                "품절 또는 입고 지연으로 주문이 보류될 경우, 영업일 기준 1일 이내 고객에게 개별 연락하여 대기 여부 또는 취소/환불 의사를 확인합니다. 3영업일 이내 응답이 없으면 자동 취소 처리됩니다.",
                                "해외 배송지로의 주문은 현재 지원하지 않으며, 국내 주소만 배송 가능합니다. 배송지는 등록된 주소록에서 선택하거나 새 주소를 직접 입력할 수 있습니다.",
                                "복수 상품을 한 번에 주문한 경우, 재고 상황에 따라 분할 배송될 수 있습니다. 분할 배송 시 추가 배송비는 부과되지 않으며, 고객에게 사전 안내합니다."
                        )
                ),

                new CreateManualDocumentRequest(
                        "배송 정책 및 오배송 처리 기준",
                        InquiryCategory.DELIVERY,
                        join(
                                "기본 배송 기간은 결제 완료 후 영업일 기준 2~3일이며, 도서산간 지역은 추가 1~2일이 소요됩니다. 배송 현황은 주문/배송 조회에서 실시간으로 확인할 수 있습니다.",
                                "배송비는 30,000원 이상 구매 시 무료이며, 미만 시 3,000원이 부과됩니다. 일부 특수 상품(가구, 대형가전 등)은 별도 배송비 정책이 적용되며 상품 페이지에 명시됩니다.",
                                "배송 중 주소 변경은 출고 전까지만 가능합니다. 출고 이후에는 택배사에 직접 연락하여 배송지 변경을 요청해야 하며, 이 경우 추가 비용이 발생할 수 있습니다.",
                                "오배송이 발생한 경우 고객센터에 즉시 신고해 주세요. 고객 귀책이 없는 오배송은 회사 비용으로 수거 및 재배송 처리합니다. 재배송은 수거 확인 후 영업일 기준 1~2일 이내 발송됩니다.",
                                "배송 사고(분실, 파손)가 발생한 경우, 사진 증거를 고객센터에 제출하면 택배사 조사 후 보상 처리됩니다. 조사 기간은 영업일 기준 최대 5일이며, 결과에 따라 교환 또는 환불로 처리됩니다.",
                                "무인 택배함 또는 경비실 배치 요청 시 메모란에 요청 사항을 기재해 주세요. 단, 이 경우 분실에 대한 책임은 고객에게 있습니다.",
                                "반복 배송 지연이나 택배사 귀책 사유로 인한 지연은 피해 보상 규정에 따라 처리하며, 기준일 초과 시 배송비 환급 및 사과 쿠폰이 발급됩니다."
                        )
                ),

                new CreateManualDocumentRequest(
                        "반품 정책 및 처리 기준",
                        InquiryCategory.RETURN,
                        join(
                                "반품은 상품 수령일로부터 7일 이내에 신청 가능합니다. 단, 상품 하자 또는 오배송의 경우 30일 이내 신청 가능합니다.",
                                "반품 가능 조건: 미사용 상태, 원 포장 유지, 구성품 전체 포함. 다음의 경우 반품이 불가합니다: 고객 사용으로 가치가 현저히 감소한 경우, 포장 개봉으로 재판매가 불가한 경우(속옷, 식품, 디지털 콘텐츠 등), 고객 주문 제작 상품.",
                                "반품 배송비는 고객 변심의 경우 고객 부담(편도 3,000원)이며, 상품 하자 또는 오배송의 경우 회사 부담입니다. 반품 택배사는 CJ대한통운을 기본으로 사용하며, 예외 시 고객센터에서 안내합니다.",
                                "반품 신청은 마이페이지 > 주문/배송 조회 > 반품/교환 신청에서 진행하거나, 고객센터를 통해 접수합니다. 신청 후 영업일 기준 1~2일 이내 수거 연락이 옵니다.",
                                "상품 불량 또는 하자가 의심될 경우, 수령 즉시 사진을 찍어 고객센터에 제출해 주세요. 증거 사진 없이 7일 경과 후 접수되는 하자 주장은 처리가 어려울 수 있습니다.",
                                "반품 수거 완료 후 검수 과정을 거치며, 검수 결과에 따라 환불 또는 반품 거절 처리됩니다. 검수 기간은 수거 완료 후 영업일 기준 1~3일입니다.",
                                "반품 거절 사유가 발생한 경우 고객에게 사진과 함께 사유를 안내하며, 이의 신청은 안내 수령 후 3영업일 이내에 가능합니다."
                        )
                ),

                new CreateManualDocumentRequest(
                        "교환 정책 및 처리 기준",
                        InquiryCategory.EXCHANGE,
                        join(
                                "교환은 상품 수령일로부터 7일 이내에 신청 가능하며, 동일 상품의 다른 옵션(사이즈, 색상 등)으로만 교환됩니다. 다른 상품으로의 교환은 반품 후 재구매로 처리합니다.",
                                "교환 가능 조건: 미사용 상태, 원 포장 유지, 구성품 전체 포함. 단순 변심 교환은 재고 보유 시에만 가능하며, 재고 부족 시 환불로 처리됩니다.",
                                "교환 배송비는 고객 변심의 경우 왕복 6,000원이며, 상품 하자 또는 오배송의 경우 회사 부담입니다.",
                                "교환 신청은 마이페이지 > 주문/배송 조회 > 반품/교환 신청에서 진행합니다. 신청 후 기존 상품 수거와 동시에 교환 상품 발송을 원칙으로 하며, 재고 상황에 따라 수거 완료 후 발송될 수 있습니다.",
                                "교환 상품의 재고가 없을 경우 고객에게 안내 후 환불로 전환됩니다. 이 경우 별도의 추가 배송비는 부과되지 않습니다.",
                                "사이즈 또는 색상 오주문의 경우, 교환 가능 기간 내 1회에 한해 무료 교환 서비스를 제공합니다. 단, 이 정책은 회원 전용이며 연 2회로 제한됩니다.",
                                "교환 상품 수령 후 재교환은 원칙적으로 불가합니다. 교환 상품에 하자가 있는 경우 예외적으로 재교환 또는 환불 처리합니다."
                        )
                ),

                new CreateManualDocumentRequest(
                        "환불 처리 정책 및 소요 기간",
                        InquiryCategory.REFUND,
                        join(
                                "환불은 반품 수거 완료 후 검수를 거쳐 처리됩니다. 검수 완료 후 영업일 기준 1~3일 이내 환불 신청이 되며, 실제 계좌/카드 반영은 결제 수단별로 다릅니다.",
                                "결제 수단별 환불 소요 기간: 신용카드/체크카드는 영업일 기준 3~5일(카드사 처리 기간 포함), 무통장 입금은 영업일 기준 1~3일(등록 계좌로 이체), 포인트 결제는 즉시 포인트로 환원, 간편결제(카카오페이, 네이버페이 등)는 각 플랫폼 정책에 따라 1~7일 소요.",
                                "부분 환불의 경우, 취소된 상품 금액에서 해당 비율의 할인 금액을 차감하여 환불됩니다. 무료배송 조건이 미충족되는 경우 배송비 3,000원이 차감될 수 있습니다.",
                                "쿠폰 사용 시 환불: 쿠폰으로 할인받은 금액은 환불되지 않습니다. 단, 상품 하자나 오배송으로 인한 환불의 경우 동일 조건의 쿠폰을 재발급합니다.",
                                "포인트 사용 시 환불: 포인트로 결제한 금액은 포인트로 환원되며, 적립 포인트는 취소됩니다. 단, 적립 후 사용된 포인트가 있는 경우 환불 금액에서 차감될 수 있습니다.",
                                "환불 지연이 발생한 경우 고객센터에 문의 시, 환불 처리 고유번호를 통해 현황을 조회하고 카드사/은행에 확인 요청을 진행합니다. 처리 지연 기준일(카드 5영업일, 계좌 3영업일) 초과 시 우선 처리로 진행합니다.",
                                "주문 취소 즉시 환불의 경우(결제 당일 취소): 카드사 승인 취소로 처리되어 청구 없이 종료됩니다. 이미 승인된 경우 통상 환불 절차와 동일하게 처리됩니다."
                        )
                ),

                new CreateManualDocumentRequest(
                        "결제 수단 및 오류 처리 정책",
                        InquiryCategory.PAYMENT,
                        join(
                                "지원 결제 수단: 신용카드(국내 전 카드사), 체크카드, 무통장 입금(가상계좌), 카카오페이, 네이버페이, 토스페이, 포인트, 상품권. 무통장 입금 결제 시 가상계좌 발급 후 24시간 이내 입금하지 않으면 자동 취소됩니다.",
                                "할부는 신용카드에 한해 2~24개월 선택 가능하며, 5만원 이상 결제 시 이용 가능합니다. 무이자 할부 행사 기간은 이벤트 페이지에서 확인할 수 있습니다.",
                                "결제 오류 발생 시 처리 절차: 1) 카드사/은행 오류는 해당 기관에 문의, 2) 이중 청구 발생 시 즉시 고객센터 신고 후 영업일 1일 이내 확인 및 취소 처리, 3) PG사 오류는 내부 확인 후 영업일 기준 1~2일 이내 처리.",
                                "이중 결제가 확인된 경우 중복 결제액을 우선 환불 처리하며, 고객에게 사과 포인트를 지급합니다. 이중 결제 사실이 확인되지 않을 경우 카드사와 협의하여 처리합니다.",
                                "결제 정보 보안: 카드번호 등 결제 정보는 암호화하여 처리하며 서버에 저장하지 않습니다. 결제 이상이 의심될 경우 즉시 고객센터 또는 카드사에 신고해 주세요.",
                                "법인/사업자 결제 시 세금계산서 발행이 가능하며, 구매 확정 후 마이페이지에서 신청할 수 있습니다. 발행은 신청 후 영업일 기준 3일 이내 처리됩니다.",
                                "미성년자의 결제 취소 요청이 있는 경우, 법정대리인 동의서 제출 확인 후 취소 처리합니다. 관련 서류는 고객센터 이메일로 제출해 주세요."
                        )
                ),

                new CreateManualDocumentRequest(
                        "상품 정보 및 재고 관련 정책",
                        InquiryCategory.PRODUCT,
                        join(
                                "상품 정보(소재, 규격, 성분 등)는 상품 상세 페이지에 기재되어 있습니다. 페이지 내 정보가 불충분하거나 알레르기 등 안전 관련 문의는 고객센터를 통해 즉시 확인해 드립니다.",
                                "알레르기 유발 성분이 포함된 상품은 상품 상세 페이지 내 '주의사항' 항목에 별도 표기합니다. 식품, 화장품, 생활용품 등 성분 민감 카테고리는 반드시 구매 전 확인을 권장합니다.",
                                "상품 사진과 실제 색상은 모니터 환경에 따라 다를 수 있습니다. 색상 차이를 이유로 한 반품/교환은 '단순 변심' 기준이 적용됩니다. 단, 상품 페이지에 명시된 색상과 현저히 다를 경우 오배송/불량 기준으로 처리합니다.",
                                "품절 상품의 재입고 알림은 상품 페이지에서 '재입고 알림 신청'을 통해 등록할 수 있습니다. 재입고 시 등록된 이메일/SMS로 즉시 안내됩니다. 재입고 예정 시기는 입고 확정 전까지 안내가 어렵습니다.",
                                "일부 상품은 구매 수량 제한이 있을 수 있습니다(1인당 최대 구매 수량 제한). 이는 공정한 구매 기회를 위한 정책이며, 제한 정보는 상품 상세 페이지에 표기됩니다.",
                                "상품 설명 오류(소재, 규격 등 잘못된 정보 표기)로 인한 구매 피해는 회사 귀책으로 처리하며, 반품/교환 시 배송비 전액 부담 및 사과 포인트를 지급합니다.",
                                "단종/판매 종료 상품에 대한 문의는 유사 대체 상품을 안내하는 방향으로 처리합니다. 단종 공지는 상품 상세 페이지 및 공지사항에서 최소 7일 전 안내합니다."
                        )
                ),

                new CreateManualDocumentRequest(
                        "회원 혜택, 포인트 및 쿠폰 정책",
                        InquiryCategory.MEMBERSHIP,
                        join(
                                "회원 등급은 일반(기본), 실버(연 30만원 이상), 골드(연 80만원 이상), VIP(연 150만원 이상)로 구분됩니다. 등급은 직전 12개월 구매 실적을 기준으로 매월 1일 자동 조정됩니다.",
                                "포인트는 구매 확정 금액의 1%(일반), 1.5%(실버), 2%(골드), 3%(VIP) 적립됩니다. 구매 확정은 배송 완료 후 7일 이내 구매확정 버튼을 누르거나 자동 확정(배송완료 후 14일 경과) 시점에 이루어집니다.",
                                "포인트 유효기간은 적립일로부터 2년이며, 유효기간 만료 30일 전 이메일/앱 푸시로 안내됩니다. 만료된 포인트는 복구되지 않으니 사전 사용을 권장합니다. 단, 시스템 오류로 인한 소멸은 고객센터 확인 후 복구 가능합니다.",
                                "포인트는 1포인트 = 1원으로 사용 가능하며, 최소 사용 금액은 1,000포인트입니다. 결제 금액의 최대 50%까지 포인트로 결제 가능합니다.",
                                "쿠폰 사용 조건: 쿠폰마다 최소 주문 금액, 할인 한도, 사용 가능 카테고리, 중복 사용 여부가 다릅니다. 쿠폰 유효기간은 발급일로부터 30일이 기본이며, 이벤트 쿠폰은 별도 기간이 적용됩니다.",
                                "쿠폰이 적용되지 않는 경우 확인 사항: 유효기간 만료 여부, 최소 주문 금액 충족 여부, 해당 상품/카테고리 적용 가능 여부, 중복 사용 불가 쿠폰 중복 적용 여부. 위 조건 확인 후에도 적용 안 되면 고객센터에 문의해 주세요.",
                                "회원 탈퇴 시 보유 포인트 및 쿠폰은 모두 소멸되며 복구되지 않습니다. 탈퇴 전 포인트 사용을 권장하며, 탈퇴 후 30일간은 재가입이 불가합니다."
                        )
                ),

                new CreateManualDocumentRequest(
                        "고객 불만 및 민원 처리 기준",
                        InquiryCategory.COMPLAINT,
                        join(
                                "고객 불만 접수 시 감정적 대응을 최대한 자제하고, 고객의 불편 사항을 먼저 경청합니다. 고객이 흥분 상태일 경우 공감 표현으로 감정을 완화한 뒤 사실 관계를 확인합니다.",
                                "불만 처리 단계: 1) 접수 및 공감 표현, 2) 사실 관계 확인(주문번호, 날짜, 담당자 등), 3) 처리 방안 제시, 4) 처리 완료 후 결과 안내, 5) 재발 방지 팀 내 공유. 각 단계별 처리 기한을 고객에게 명확히 안내합니다.",
                                "상담사 응대 불량 민원의 경우: 즉시 팀장급 이상에게 에스컬레이션하고, 해당 상담사에게 피드백을 제공합니다. 고객에게는 사과와 함께 재발 방지를 약속하며, 사과 포인트(3,000~10,000포인트)를 지급합니다.",
                                "동일 고객이 반복 민원을 접수하는 경우, 이전 접수 내역을 확인하고 담당자를 지정하여 연속성 있게 처리합니다. 3회 이상 반복 민원은 팀장이 직접 처리합니다.",
                                "법적 분쟁으로 확대될 우려가 있는 민원은 법무팀에 즉시 공유하고, 관련 대화 내용 및 증거를 보존합니다. 이 경우 고객에게 추가 처리 기간(영업일 5일)이 필요함을 안내합니다.",
                                "SNS 또는 공개 채널에 올라온 불만 게시물은 마케팅팀과 협력하여 대응합니다. 공개 답변은 방어적 표현을 피하고 신속한 해결 의지를 표현합니다.",
                                "민원 처리 결과는 CRM 시스템에 기록하고, 월 1회 민원 유형별 분석 리포트를 팀에 공유합니다. 반복 발생하는 유형은 프로세스 개선 과제로 등록합니다."
                        )
                ),

                new CreateManualDocumentRequest(
                        "고객센터 운영 및 일반 안내",
                        InquiryCategory.GENERAL,
                        join(
                                "고객센터 운영 시간: 평일 오전 9시 ~ 오후 6시(점심 12시~1시 제외). 주말 및 공휴일은 운영하지 않으며, 이 시간 외에 접수된 문의는 익일 영업일에 처리됩니다.",
                                "문의 채널: 앱/웹 채팅 상담(운영 시간 내 실시간), 이메일(cs@shopease.co.kr, 영업일 1일 이내 회신), 전화(1588-XXXX, 운영 시간 내), 카카오톡 채널(운영 시간 내). 문의 유형별 최적 채널을 안내하여 처리 속도를 높입니다.",
                                "대량구매 및 기업 고객 문의는 B2B 전담팀(b2b@shopease.co.kr)으로 연결합니다. 세금계산서 발행, 단가 협의, 전용 계좌 등 기업 전용 서비스를 제공합니다. 법인 첫 거래 시 사업자등록증 사본 제출이 필요합니다.",
                                "고객센터 채팅 대기 시간이 길 경우, 예상 대기 시간을 안내하고 콜백 서비스 이용을 권유합니다. 콜백은 영업 시간 내 30분 이내 연락을 원칙으로 합니다.",
                                "자주 묻는 질문(FAQ)은 앱/웹 고객센터 페이지에서 확인할 수 있습니다. 주문, 배송, 반품, 결제 카테고리별로 정리되어 있으며, 야간/주말에도 이용 가능합니다.",
                                "개인정보 관련 문의(열람, 수정, 삭제, 동의 철회)는 개인정보 처리 담당자(privacy@shopease.co.kr)에게 별도 접수합니다. 처리는 접수 후 영업일 기준 5일 이내를 원칙으로 합니다.",
                                "장애 또는 시스템 오류로 인한 서비스 불편 사항은 우선순위로 처리하며, 공지사항 페이지에 장애 현황을 실시간으로 업데이트합니다."
                        )
                )
        );

        for (CreateManualDocumentRequest manual : manuals) {
            manualService.create(manual);
        }
    }

    private String join(String... paragraphs) {
        return String.join("\n\n", paragraphs);
    }
}
