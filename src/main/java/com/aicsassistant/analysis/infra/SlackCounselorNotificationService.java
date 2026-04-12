package com.aicsassistant.analysis.infra;

import com.aicsassistant.analysis.application.CounselorNotificationService;
import com.aicsassistant.common.config.SlackProperties;
import com.aicsassistant.inquiry.domain.Inquiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class SlackCounselorNotificationService implements CounselorNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SlackCounselorNotificationService.class);

    private final SlackProperties slackProperties;
    private final WebClient webClient;

    public SlackCounselorNotificationService(SlackProperties slackProperties, WebClient webClient) {
        this.slackProperties = slackProperties;
        this.webClient = webClient;
    }

    @Override
    public void notifyHumanReviewRequired(Inquiry inquiry, String reason) {
        log.warn("[검토 필요] inquiryId={} title='{}' category={} urgency={} reason={}",
                inquiry.getId(), inquiry.getTitle(),
                inquiry.getCategory(), inquiry.getUrgency(), reason);

        sendSlack("""
                :eyes: *[검토 필요] 문의 #%d — %s*
                > 카테고리: `%s` | 긴급도: `%s`
                > 사유: %s
                """.formatted(
                inquiry.getId(), inquiry.getTitle(),
                inquiry.getCategory(), inquiry.getUrgency(), reason));
    }

    @Override
    public void notifyEscalationRequired(Inquiry inquiry, String reason) {
        log.error("[에스컬레이션] inquiryId={} title='{}' category={} urgency={} reason={}",
                inquiry.getId(), inquiry.getTitle(),
                inquiry.getCategory(), inquiry.getUrgency(), reason);

        sendSlack("""
                :rotating_light: *[에스컬레이션] 문의 #%d — %s*
                > 카테고리: `%s` | 긴급도: `%s`
                > 사유: %s
                > *즉시 매니저 확인 필요*
                """.formatted(
                inquiry.getId(), inquiry.getTitle(),
                inquiry.getCategory(), inquiry.getUrgency(), reason));
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
