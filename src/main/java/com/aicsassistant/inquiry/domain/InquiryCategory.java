package com.aicsassistant.inquiry.domain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public enum InquiryCategory {
    ORDER("주문"),
    DELIVERY("배송"),
    RETURN("반품"),
    EXCHANGE("교환"),
    REFUND("환불"),
    PAYMENT("결제"),
    PRODUCT("상품"),
    MEMBERSHIP("회원/계정"),
    COMPLAINT("불만/건의"),
    GENERAL("기타");

    private final String label;

    InquiryCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 이름 → 라벨. 템플릿이 문자열 키로 조회한다 — 대시보드 집계는 enum 이 아니라 문자열로 온다.
     */
    public static Map<String, String> labels() {
        return Arrays.stream(values())
                .collect(Collectors.toMap(Enum::name, InquiryCategory::getLabel, (a, b) -> a, LinkedHashMap::new));
    }
}
