package com.aicsassistant.analysis.agent;

/**
 * 에이전트가 호출 가능한 도구의 인터페이스.
 *
 * <p>모델은 도구의 구현 코드가 아닌 {@link #name()}, {@link #description()},
 * {@link #whenToUse()}, {@link #inputSchema()}, {@link #outputSchemaHint()}로
 * 구성된 표면(surface)만 보고 호출 여부를 결정한다. 따라서 각 메서드는
 * "모델이 무엇을 보아야 하는가"를 기준으로 작성한다.
 *
 * <p>입력은 {@link #inputType()}이 가리키는 타입으로 역직렬화되어
 * 타입 안전하게 전달된다.
 *
 * @param <I> 도구별 입력 record 타입
 */
public interface AgentTool<I> {

    /** 모델이 action 필드에 적을 도구 이름 (snake_case). */
    String name();

    /** 도구가 무엇을 하는지 한 문장으로 설명 (모델용). */
    String description();

    /** 모델이 이 도구를 언제 호출해야 하는지 안내 (모델용). */
    String whenToUse();

    /**
     * "이 도구를 쓰지 말아야 할 때" 또는 "유사 도구와의 경계" 명시.
     * 가이드 4번: 경계가 없으면 모델이 가장 '그럴듯한' 도구를 잘못 고른다.
     */
    String usageBoundary();

    /** 입력 record 클래스. {@code ObjectMapper.treeToValue}로 변환된다. */
    Class<I> inputType();

    /**
     * 입력 스키마를 모델에게 노출할 JSON 문자열.
     * 예: {@code {"query": "string (required) — 한국어 키워드"}}
     */
    String inputSchema();

    /**
     * 성공 시 {@code ToolResult.data} 필드에 들어오는 텍스트의 형태 설명.
     * 모델이 첫 호출 전에 결과 형태를 미리 알 수 있도록 한다.
     */
    String successOutputHint();

    /**
     * 실패/엣지 케이스에서 모델이 어떻게 행동해야 하는지 안내.
     * 예: "NOT_FOUND → followUpQuestion으로 ID 재확인", "VALIDATION → actionInput 수정 후 재시도"
     */
    String failureBehavior();

    /** 타입 안전한 실행. 입력 검증 실패 시 {@link ToolResult#error}를 반환한다. */
    ToolResult execute(I input);
}
