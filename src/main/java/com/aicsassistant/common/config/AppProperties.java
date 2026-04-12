package com.aicsassistant.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String baseUrl = "http://localhost:8080";

    public String inquiryDetailUrl(Long inquiryId) {
        return baseUrl + "/ui/inquiries/" + inquiryId;
    }
}
