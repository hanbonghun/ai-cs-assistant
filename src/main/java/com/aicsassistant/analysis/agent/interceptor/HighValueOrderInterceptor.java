package com.aicsassistant.analysis.agent.interceptor;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolCallInterceptor;
import com.aicsassistant.analysis.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code check_order_status} 결과의 결제금액이 임계 이상이면 정책 가드 노트를 부착한다.
 *
 * <p>고액 주문에 대한 환불·취소·교환 요청을 LLM이 자동 처리하지 않도록 finalAnswer에서
 * needsHumanReview/needsEscalation을 true로 설정하라는 지시를 데이터 끝에 추가한다.
 */
@Component
public class HighValueOrderInterceptor implements ToolCallInterceptor {

    static final long HIGH_VALUE_THRESHOLD_KRW = 1_000_000L;
    private static final String TARGET_TOOL = "check_order_status";
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("결제금액:\\s*([\\d,]+)\\s*원");

    @Override
    public ToolResult afterExecute(String toolName, JsonNode input, ToolResult result, ToolCallContext ctx) {
        if (!TARGET_TOOL.equals(toolName) || !result.ok() || result.data() == null) {
            return result;
        }

        Matcher m = AMOUNT_PATTERN.matcher(result.data());
        if (!m.find()) {
            return result;
        }

        long amount;
        try {
            amount = Long.parseLong(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return result;
        }

        if (amount < HIGH_VALUE_THRESHOLD_KRW) {
            return result;
        }

        String guard = "\n\n[정책 가드: 결제금액 "
                + String.format("%,d", amount)
                + "원은 고액 주문 임계("
                + String.format("%,d", HIGH_VALUE_THRESHOLD_KRW)
                + "원) 이상입니다. 환불·취소·교환 요청에 대해 자동 처리하지 말고, finalAnswer에서 "
                + "needsHumanReview: true와 needsEscalation: true로 설정해 상담사에게 라우팅하세요.]";
        return ToolResult.success(result.data() + guard);
    }
}
