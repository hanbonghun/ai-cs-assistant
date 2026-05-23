package com.aicsassistant.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LangfusePropertiesTest {

    @Test
    void isConfigured_returnsFalse_whenDisabled() {
        LangfuseProperties props = new LangfuseProperties();
        props.setEnabled(false);
        props.setPublicKey("pk");
        props.setSecretKey("sk");

        assertThat(props.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_returnsFalse_whenPublicKeyMissing() {
        LangfuseProperties props = new LangfuseProperties();
        props.setEnabled(true);
        props.setPublicKey("");
        props.setSecretKey("sk");

        assertThat(props.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_returnsFalse_whenSecretKeyBlank() {
        LangfuseProperties props = new LangfuseProperties();
        props.setEnabled(true);
        props.setPublicKey("pk");
        props.setSecretKey("   ");

        assertThat(props.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_returnsTrue_whenAllSet() {
        LangfuseProperties props = new LangfuseProperties();
        props.setEnabled(true);
        props.setPublicKey("pk-lf-xxx");
        props.setSecretKey("sk-lf-xxx");

        assertThat(props.isConfigured()).isTrue();
    }

    @Test
    void otlpTracesEndpoint_appendsPath_withoutDoubleSlash() {
        LangfuseProperties props = new LangfuseProperties();
        props.setHost("https://jp.cloud.langfuse.com/");

        assertThat(props.otlpTracesEndpoint())
                .isEqualTo("https://jp.cloud.langfuse.com/api/public/otel/v1/traces");
    }

    @Test
    void otlpTracesEndpoint_appendsPath_whenNoTrailingSlash() {
        LangfuseProperties props = new LangfuseProperties();
        props.setHost("https://jp.cloud.langfuse.com");

        assertThat(props.otlpTracesEndpoint())
                .isEqualTo("https://jp.cloud.langfuse.com/api/public/otel/v1/traces");
    }
}
