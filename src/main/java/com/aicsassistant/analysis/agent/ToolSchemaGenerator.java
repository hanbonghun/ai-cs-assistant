package com.aicsassistant.analysis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 도구 입력 record 로부터 JSON Schema 를 만든다.
 *
 * <p>이전에는 각 도구가 {@code inputSchema()} 로 같은 정보를 손으로 한 번 더 적었다. record 에
 * 필드를 더해도 그 문자열은 컴파일러가 보지 않으므로, 실제 Java 타입과 모델이 보는 스키마가
 * 조용히 어긋날 수 있었다. 이제 {@link AgentTool#inputType()} 이 유일한 출처다.
 *
 * <p>모르는 타입은 그럴듯한 스키마를 만들어내지 않고 던진다. 잘못된 스키마는 모델이 잘못된
 * 인자를 만들게 하고, 그 실패는 도구 실행 시점까지 미뤄져 원인을 찾기 어렵다.
 *
 * <p>표준 JSON Schema 를 만든다 — OpenAI strict 모드가 요구하는 변형(모든 필드를 required 에
 * 넣고 optional 은 nullable 유니온으로 적는 규칙)은 provider 어댑터의 몫이지 여기가 아니다.
 */
@Component
@RequiredArgsConstructor
public class ToolSchemaGenerator {

    private final ObjectMapper objectMapper;

    /** {@code inputType} 의 record component 만 읽어 스키마를 만든다. */
    public JsonNode generate(Class<?> inputType) {
        return buildObject(inputType);
    }

    private ObjectNode buildObject(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(
                    "Tool input must be a record, but was: " + type.getSimpleName());
        }

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = objectMapper.createArrayNode();

        for (RecordComponent component : type.getRecordComponents()) {
            ToolParam param = component.getAnnotation(ToolParam.class);
            if (param == null) {
                throw new IllegalArgumentException(
                        "Missing @ToolParam on %s.%s — a field the model sees by name alone is the same "
                                .formatted(type.getSimpleName(), component.getName())
                                + "silent mismatch this generator exists to remove.");
            }
            ObjectNode property = describe(component.getType());
            property.put("description", param.description());
            properties.set(component.getName(), property);
            if (param.required()) {
                required.add(component.getName());
            }
        }

        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode describe(Class<?> type) {
        if (type.isRecord()) {
            return buildObject(type);
        }
        ObjectNode node = objectMapper.createObjectNode();
        if (type.isEnum()) {
            node.put("type", "string");
            ArrayNode values = node.putArray("enum");
            for (Object constant : type.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return node;
        }
        node.put("type", jsonType(type));
        return node;
    }

    private String jsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "integer";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == double.class || type == Double.class || type == BigDecimal.class) {
            return "number";
        }
        throw new IllegalArgumentException(
                "Unsupported tool input type: " + type.getSimpleName()
                        + ". Add it to ToolSchemaGenerator rather than letting a wrong schema through.");
    }
}
