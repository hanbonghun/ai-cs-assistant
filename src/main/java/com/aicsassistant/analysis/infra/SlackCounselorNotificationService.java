package com.aicsassistant.analysis.infra;

import com.aicsassistant.analysis.application.CounselorNotificationService;
import com.aicsassistant.common.config.AppProperties;
import com.aicsassistant.common.config.SlackProperties;
import com.aicsassistant.inquiry.domain.Inquiry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlackCounselorNotificationService implements CounselorNotificationService {

    private final SlackProperties slackProperties;
    private final AppProperties appProperties;
    private final WebClient webClient;

    @Override
    public void notifyHumanReviewRequired(Inquiry inquiry, String reason) {
        log.warn("[검토 필요] inquiryId={} title='{}' category={} urgency={} reason={}",
                inquiry.getId(), inquiry.getTitle(),
                inquiry.getCategory(), inquiry.getUrgency(), reason);

        String detailUrl = appProperties.inquiryDetailUrl(inquiry.getId());
        String contextBlock = buildContextBlock(inquiry);

        String text = """
                :eyes: *[검토 필요] 문의 #%d — %s*
                > 카테고리: `%s` | 긴급도: `%s`
                > 사유: %s
                %s
                <%s|상세 화면 바로가기 →>
                """.formatted(
                inquiry.getId(), inquiry.getTitle(),
                inquiry.getCategory(), inquiry.getUrgency(),
                reason,
                contextBlock,
                detailUrl);

        sendSlack(text);
    }

    @Override
    public void notifyEscalationRequired(Inquiry inquiry, String reason) {
        log.error("[에스컬레이션] inquiryId={} title='{}' category={} urgency={} reason={}",
                inquiry.getId(), inquiry.getTitle(),
                inquiry.getCategory(), inquiry.getUrgency(), reason);

        String detailUrl = appProperties.inquiryDetailUrl(inquiry.getId());
        String contextBlock = buildContextBlock(inquiry);

        String text = """
                :rotating_light: *[에스컬레이션] 문의 #%d — %s*
                > 카테고리: `%s` | 긴급도: `%s`
                > 사유: %s
                %s
                <%s|상세 화면 바로가기 →>
                *즉시 매니저 확인 필요*
                """.formatted(
                inquiry.getId(), inquiry.getTitle(),
                inquiry.getCategory(), inquiry.getUrgency(),
                reason,
                contextBlock,
                detailUrl);

        sendSlack(text);
    }

    /**
     * AI가 대화를 통해 수집한 컨텍스트(초안 답변)를 Slack 블록으로 변환한다.
     * aiDraftAnswer에는 에이전트가 수집한 주문 상태, 정책 내용 등이 요약되어 있다.
     */
    private String buildContextBlock(Inquiry inquiry) {
        String draft = inquiry.getAiDraftAnswer();
        if (draft == null || draft.isBlank()) {
            return "";
        }
        // 너무 길면 앞 200자만
        String preview = draft.length() > 200 ? draft.substring(0, 200) + "..." : draft;
        return "\n> :memo: *AI 수집 컨텍스트*\n> " + preview.replace("\n", "\n> ") + "\n";
    }

    private void sendSlack(String text) {
        if (!slackProperties.isConfigured()) {
            return;
        }
        try {
            String body = "{\"text\": " + toJsonString(text.strip()) + "}";
            webClient.post()
                    .uri(slackProperties.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            log.error("Slack 알림 전송 실패: {}", e.getMessage());
        }
    }

    private String toJsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                + "\"";
    }
}
