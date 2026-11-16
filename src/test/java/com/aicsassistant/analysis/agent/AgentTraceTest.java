package com.aicsassistant.analysis.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentTraceTest {

    @Test
    void run_marksSpanErrorAndRethrows_whenBodyThrows() {
        List<SpanData> exported = new ArrayList<>();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new CollectingExporter(exported)))
                .build();
        Tracer tracer = provider.get("test");
        IllegalStateException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> AgentTrace.run(tracer, Inquiry.create("kim", "t", "c"), trace -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(exported).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("inquiry-analysis-agent");
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
        });
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void buildTags_returnsEmptyList_whenCategoryAndUrgencyNull() {
        assertThat(AgentTrace.buildTags(null, null)).isEmpty();
    }

    @Test
    void buildTags_returnsEmptyList_whenBlank() {
        assertThat(AgentTrace.buildTags("", "  ")).isEmpty();
    }

    @Test
    void buildTags_includesCategoryOnly_whenUrgencyNull() {
        assertThat(AgentTrace.buildTags(InquiryCategory.REFUND.name(), null))
                .containsExactly("category:REFUND");
    }

    @Test
    void buildTags_includesUrgencyOnly_whenCategoryNull() {
        assertThat(AgentTrace.buildTags(null, UrgencyLevel.HIGH.name()))
                .containsExactly("urgency:HIGH");
    }

    @Test
    void buildTags_includesBoth_inOrder() {
        assertThat(AgentTrace.buildTags(
                InquiryCategory.EXCHANGE.name(), UrgencyLevel.MEDIUM.name()))
                .containsExactly("category:EXCHANGE", "urgency:MEDIUM");
    }

    @Test
    void safeUserId_returnsAnonymous_whenCustomerNull() {
        Inquiry inquiry = Inquiry.create(null, "t", "c");

        assertThat(AgentTrace.safeUserId(inquiry)).isEqualTo("anonymous");
    }

    @Test
    void safeUserId_returnsAnonymous_whenCustomerBlank() {
        Inquiry inquiry = Inquiry.create("   ", "t", "c");

        assertThat(AgentTrace.safeUserId(inquiry)).isEqualTo("anonymous");
    }

    @Test
    void safeUserId_returnsRawId_whenCustomerSet() {
        Inquiry inquiry = Inquiry.create("kim-minjun", "t", "c");

        assertThat(AgentTrace.safeUserId(inquiry)).isEqualTo("kim-minjun");
    }

    private record CollectingExporter(List<SpanData> sink) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            sink.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
