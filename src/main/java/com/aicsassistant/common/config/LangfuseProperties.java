package com.aicsassistant.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.langfuse")
public class LangfuseProperties {

    private boolean enabled = true;
    private String host = "https://jp.cloud.langfuse.com";
    private String publicKey = "";
    private String secretKey = "";
    private String serviceName = "ai-cs-assistant";

    public boolean isConfigured() {
        return enabled
                && publicKey != null && !publicKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    public String otlpTracesEndpoint() {
        return host.replaceAll("/$", "") + "/api/public/otel/v1/traces";
    }
}
