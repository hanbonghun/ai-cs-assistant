package com.aicsassistant.analysis.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.analysis.agent.tool.CheckOrderStatusTool;
import com.aicsassistant.analysis.agent.tool.SearchManualTool;
import com.aicsassistant.analysis.agent.tool.StageRefundTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 입력 record 로부터 JSON Schema 를 만드는 생성기 테스트.
 *
 * <p>이 클래스가 존재하는 이유가 곧 이 테스트가 지키는 것이다 — record 와 손으로 쓴 스키마
 * 문자열이 어긋나도 컴파일러가 잡지 못하던 문제. 이제 record 가 유일한 출처다.
 */
class ToolSchemaGeneratorTest {

    private final ToolSchemaGenerator generator = new ToolSchemaGenerator(new ObjectMapper());

    @Test
    void generatesStringPropertyWithDescriptionFromAnnotation() {
        JsonNode schema = generator.generate(CheckOrderStatusTool.Input.class);

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").path("orderId").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("orderId").path("description").asText()).isNotBlank();
    }

    @Test
    void marksAnnotatedComponentAsRequired() {
        JsonNode schema = generator.generate(CheckOrderStatusTool.Input.class);

        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("orderId");
    }

    @Test
    void forbidsUndeclaredProperties() {
        JsonNode schema = generator.generate(CheckOrderStatusTool.Input.class);

        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    /**
     * 생성자에 묶인 서버 신뢰 컨텍스트가 모델 입력으로 새지 않는지 본다 (ADR 0005).
     * record component 만 읽으므로 구조적으로 불가능하지만, 회귀하면 소유자 스코프가 뚫린다.
     */
    @Test
    void neverExposesServerTrustedContext() {
        JsonNode schema = generator.generate(CheckOrderStatusTool.Input.class);

        assertThat(schema.path("properties").has("customerIdentifier")).isFalse();
        assertThat(schema.toString()).doesNotContain("customerIdentifier");
    }

    @Test
    void mapsIntegerComponentAndKeepsOptionalOutOfRequired() {
        JsonNode schema = generator.generate(StageRefundTool.Input.class);

        assertThat(schema.path("properties").path("amount").path("type").asText()).isEqualTo("integer");
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("orderId", "amount", "reason")
                .doesNotContain("policyBasis");
        assertThat(schema.path("properties").has("policyBasis")).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    /** 제안이 어느 문의에 속하는지는 서버가 안다 — 모델이 정할 값이 아니다. */
    @Test
    void neverExposesInquiryIdBoundToTheToolInstance() {
        JsonNode schema = generator.generate(StageRefundTool.Input.class);

        assertThat(schema.toString()).doesNotContain("inquiryId");
    }

    /**
     * 설명 없는 필드는 모델에게 이름만 보인다 — 스키마를 자동 생성하게 만든 이유가
     * 그 조용한 어긋남이므로, 여기서도 조용히 넘기지 않는다.
     */
    @Test
    void rejectsComponentWithoutToolParamInsteadOfEmittingNamelessField() {
        record Undocumented(String mystery) {}

        assertThatThrownBy(() -> generator.generate(Undocumented.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mystery");
    }

    /**
     * 도구를 하나 더 붙이면서 {@link ToolParam} 을 빠뜨리면, 그 사실은 첫 에이전트 실행에서야
     * 드러난다 — 프롬프트를 만드는 시점이기 때문이다. 목록은 {@code AgentToolFactory} 와 같다.
     */
    @Test
    void everyShippedToolInputProducesADescribedSchema() {
        List<Class<?>> inputs = List.of(
                SearchManualTool.Input.class,
                CheckOrderStatusTool.Input.class,
                StageRefundTool.Input.class);

        for (Class<?> inputType : inputs) {
            assertThat(generator.generate(inputType).path("properties"))
                    .as("%s 의 스키마", inputType.getEnclosingClass().getSimpleName())
                    .isNotEmpty();
        }
    }

    @Test
    void rejectsUnsupportedJavaTypeInsteadOfEmittingWrongSchema() {
        record Unsupported(@ToolParam(description = "when did it happen") java.time.LocalDate at) {}

        assertThatThrownBy(() -> generator.generate(Unsupported.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LocalDate");
    }
}
