package com.aicsassistant.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.slack")
public class SlackProperties {

    private String webhookUrl = "";

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
