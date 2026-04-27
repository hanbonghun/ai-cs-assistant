package com.aicsassistant.faq;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 데모용 인메모리 FAQ 저장소.
 *
 * <p>실제 서비스에서는 큐레이션된 Q&A를 별도 테이블/검색 인덱스로 관리합니다.
 * 여기서는 키워드 포함 매칭으로 단순 검색을 구현합니다.
 *
 * <p>설계 의도: {@code SearchManualTool}(정책 원문 RAG)과 의도적으로 기능이 겹치도록 둠.
 * 모델이 도구 설명({@code usageBoundary})만 보고 두 도구를 구분할 수 있는지 검증하기 위한 데모.
 */
@Repository
public class InMemoryFaqRepository {

    public record FaqEntry(String question, String answer, List<String> keywords) {}

    private static final List<FaqEntry> ENTRIES = List.of(
            new FaqEntry(
                    "환불은 며칠 걸리나요?",
                    "환불 요청 승인 후 영업일 기준 2~3일 내에 결제 수단으로 환불이 완료됩니다. 카드 결제는 카드사 정책에 따라 추가 1~2일이 더 걸릴 수 있습니다.",
                    List.of("환불", "환불기간", "환불일정", "며칠")),
            new FaqEntry(
                    "배송 조회는 어디서 하나요?",
                    "마이페이지 > 주문 내역에서 운송장 번호를 확인하거나, 배송사 홈페이지에서 직접 조회 가능합니다.",
                    List.of("배송조회", "배송", "운송장", "조회")),
            new FaqEntry(
                    "회원 탈퇴는 어떻게 하나요?",
                    "마이페이지 > 설정 > 회원 탈퇴 메뉴에서 직접 탈퇴할 수 있습니다. 탈퇴 후 30일간 동일 이메일로 재가입이 제한됩니다.",
                    List.of("탈퇴", "회원탈퇴", "계정삭제")),
            new FaqEntry(
                    "비밀번호 재설정은 어떻게 하나요?",
                    "로그인 화면의 '비밀번호 찾기' → 가입 이메일 입력 → 메일로 받은 링크에서 새 비밀번호 설정.",
                    List.of("비밀번호", "패스워드", "재설정", "찾기")),
            new FaqEntry(
                    "쿠폰은 어디서 확인하나요?",
                    "마이페이지 > 쿠폰함에서 보유 중인 쿠폰과 만료일을 확인할 수 있습니다.",
                    List.of("쿠폰", "할인쿠폰", "쿠폰함")),
            new FaqEntry(
                    "적립금은 어떻게 사용하나요?",
                    "결제 화면에서 사용할 적립금을 입력하면 자동 차감됩니다. 최소 사용 금액은 1,000원이며 5,000원 단위로 사용 가능합니다.",
                    List.of("적립금", "포인트", "마일리지")),
            new FaqEntry(
                    "교환은 가능한가요?",
                    "수령일로부터 7일 이내, 상품 미사용 상태에서 교환 가능합니다. 단순 변심은 왕복 배송비가 부과됩니다.",
                    List.of("교환", "사이즈교환", "색상교환")),
            new FaqEntry(
                    "주문 취소는 어떻게 하나요?",
                    "출고 전이라면 마이페이지 > 주문 내역 > 취소 버튼으로 즉시 취소 가능합니다. 출고 후에는 반품 절차로 진행됩니다.",
                    List.of("취소", "주문취소")),
            new FaqEntry(
                    "현금영수증 발급은?",
                    "결제 시 현금영수증 신청을 체크하면 자동 발급됩니다. 사후 발급은 마이페이지 > 영수증 관리에서 가능합니다.",
                    List.of("현금영수증", "영수증", "세금계산서")),
            new FaqEntry(
                    "고객센터 운영시간은?",
                    "평일 오전 9시 ~ 오후 6시, 주말·공휴일 휴무. 채팅 상담은 24시간 가능하며 야간 문의는 다음 영업일에 순차 답변됩니다.",
                    List.of("운영시간", "고객센터", "상담시간"))
    );

    /** 키워드 또는 질문 포함 매칭으로 가장 적합한 FAQ를 반환한다. */
    public Optional<FaqEntry> findBest(String query) {
        String normalized = query.toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return ENTRIES.stream()
                .filter(e -> matches(e, normalized))
                .findFirst();
    }

    private boolean matches(FaqEntry entry, String normalizedQuery) {
        for (String kw : entry.keywords()) {
            if (normalizedQuery.contains(kw.toLowerCase(Locale.ROOT).replace(" ", ""))) {
                return true;
            }
        }
        return entry.question().toLowerCase(Locale.ROOT).replace(" ", "").contains(normalizedQuery);
    }
}
