package com.aicsassistant.common.observability;

import com.aicsassistant.common.config.LangfuseProperties;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class OpenTelemetryConfig {

    /** 단일 attribute 값 최대 길이. PII/payload bloat 방지용. */
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 8192;

    @Bean(destroyMethod = "close")
    public OpenTelemetrySdk openTelemetrySdk(LangfuseProperties props) {
        if (!props.isConfigured()) {
            log.info("Langfuse not configured (missing keys or disabled). Using no-op tracer provider.");
            // 빈 SDK도 close 가능하므로 destroyMethod 지정에 문제 없음.
            return OpenTelemetrySdk.builder().build();
        }

        String credentials = props.getPublicKey() + ":" + props.getSecretKey();
        String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(props.otlpTracesEndpoint())
                .addHeader("Authorization", "Basic " + basicAuth)
                .setCompression("gzip")
                .setTimeout(Duration.ofSeconds(5))
                .build();

        BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter)
                .setScheduleDelay(Duration.ofSeconds(2))
                .setExporterTimeout(Duration.ofSeconds(5))
                .setMaxQueueSize(2048)
                .setMaxExportBatchSize(512)
                .build();

        SpanLimits spanLimits = SpanLimits.builder()
                .setMaxAttributeValueLength(MAX_ATTRIBUTE_VALUE_LENGTH)
                .build();

        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, props.getServiceName())));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .setResource(resource)
                .setSpanLimits(spanLimits)
                .build();

        log.info("Langfuse OpenTelemetry exporter configured: {}", props.otlpTracesEndpoint());

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Bean
    public OpenTelemetry openTelemetry(OpenTelemetrySdk sdk, LangfuseProperties props) {
        if (!props.isConfigured()) {
            return OpenTelemetry.noop();
        }
        return sdk;
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("ai-cs-assistant", "0.0.1");
    }
}
