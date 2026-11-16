package com.aicsassistant.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 채팅 모델 공급자. {@code openai}(기본) 또는 {@code anthropic}. 임베딩은 공급자와 무관하게 OpenAI 다. */
    private String provider;
    private String model;
    private String apiKey;
    private String embeddingModel;
    private Anthropic anthropic = new Anthropic();

    @Getter
    @Setter
    public static class Anthropic {
        private String apiKey;
        private String model;
        /** 사고 깊이. Opus 5.5 는 사고를 끌 수 없고 effort 로만 조절한다 — 모델 기본값은 medium. */
        private String effort;
        /**
         * 안전 분류기 거절 시 서버 측 폴백을 쓸지. 운영은 켠다. 평가 judge 는 끈다 — 폴백이 돌면 다른 모델이
         * 채점했는데 기록에는 지정 모델로 남아, 고정 judge 라는 전제가 깨진다.
         */
        private boolean fallback = true;
    }
}
