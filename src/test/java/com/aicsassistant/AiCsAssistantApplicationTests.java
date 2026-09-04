package com.aicsassistant;

import com.aicsassistant.common.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    // 이 테스트는 DB 없이 컨텍스트 로딩만 본다. @Scheduled 빈은 지연 초기화를 우회해 즉시
    // 생성되므로 레포지터리를 끌어와 기동을 깨뜨린다 — DB 가 없으면 스위퍼도 없는 게 맞다.
    "app.analysis.retry.enabled=false"
})
class AiCsAssistantApplicationTests {

    @Autowired
    private AiProperties aiProperties;

    @Test
    void contextLoads() {
    }

    @Test
    void aiPropertiesBind() {
        assertThat(aiProperties.getProvider()).isEqualTo("openai");
    }
}
