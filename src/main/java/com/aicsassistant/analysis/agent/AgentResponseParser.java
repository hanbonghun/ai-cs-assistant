package com.aicsassistant.analysis.agent;

import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LLM 이 돌려준 텍스트를 에이전트가 쓸 값으로 옮긴다.
 *
 * <p>루프에서 분리한 이유는 두 가지다. 첫째, 여기 모인 규칙은 전부 <b>"모델 출력을 얼마나 관대하게
 * 받아줄 것인가"</b> 라는 한 가지 판단이다 — 마크다운 울타리를 벗기고, enum 밖 값을 안전한 기본값으로
 * 낮추고, 빠진 필수 필드는 실패로 올린다. 둘째, 전부 순수 함수라 루프를 거치지 않고 직접 테스트할 수 있다.
 *
 * <p>관대함의 방향은 한쪽으로 정해져 있다. <b>형식이 어긋나도 문의를 잃지 않는 쪽</b>이다 (ADR 0006).
 * 다만 최종 답변 본문처럼 없으면 답이 성립하지 않는 필드는 관대하게 넘기지 않고 예외로 올려,
 * 강제 종료 경로가 브리핑을 합성하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AgentResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * 스텝 응답을 JSON 으로 읽는다.
     *
     * <p>실패는 {@link IllegalStateException} 으로 올린다 — 호출부가 이것을 잡아
     * {@code AI_PARSE_ERROR} 로 바꾸고 재시도 대상으로 남긴다.
     */
    JsonNode parse(String response) {
        try {
            return objectMapper.readTree(stripMarkdownFence(response));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse agent response: " + response, e);
        }
    }

    /**
     * 프롬프트가 "raw JSON only" 를 요구하지만 모델은 종종 ```json 울타리를 붙인다.
     * 지시를 강화하는 것보다 벗겨서 받아주는 쪽이 싸다.
     */
    private String stripMarkdownFence(String response) {
        String trimmed = response.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    /** 없으면 최종 답변이 성립하지 않는 필드. 비어 있으면 강제 종료 경로로 넘긴다. */
    String requiredText(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Agent final result missing field: " + fieldName);
        }
        return value;
    }

    /**
     * enum 에 없는 값이면 GENERAL 로 낮춘다. 하위 레이어의 {@code valueOf} 가 던지면 분석 전체가
     * 실패하는데, 분류를 하나 틀리는 것보다 문의를 잃는 게 나쁘다.
     */
    String validCategory(String raw) {
        try {
            return InquiryCategory.valueOf(raw).name();
        } catch (IllegalArgumentException e) {
            log.warn("[Agent] 알 수 없는 category={} → GENERAL로 대체", raw);
            return InquiryCategory.GENERAL.name();
        }
    }

    String validUrgency(String raw) {
        try {
            return UrgencyLevel.valueOf(raw).name();
        } catch (IllegalArgumentException e) {
            log.warn("[Agent] 알 수 없는 urgency={} → MEDIUM으로 대체", raw);
            return UrgencyLevel.MEDIUM.name();
        }
    }
}
