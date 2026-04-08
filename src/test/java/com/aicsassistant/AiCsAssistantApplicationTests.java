package com.aicsassistant;

import com.aicsassistant.common.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
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
