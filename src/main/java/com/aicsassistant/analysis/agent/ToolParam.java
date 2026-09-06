package com.aicsassistant.analysis.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 도구 입력 record 의 한 필드가 모델에게 어떻게 보일지 적는다.
 *
 * <p>리플렉션만으로는 필드의 <b>의미</b>와 <b>필수 여부</b>를 알 수 없다 — 타입은 record 가 알지만
 * "이 주문은 이번 대화에서 조회된 것이어야 한다" 는 여기에만 있다. 이 둘을 합쳐
 * {@link ToolSchemaGenerator} 가 스키마를 만든다.
 *
 * <p>붙이는 곳은 record component 뿐이다. 생성자에 묶인 서버 신뢰 컨텍스트
 * (customerIdentifier, inquiryId) 는 record 밖에 있으므로 스키마에 나타날 수 없다 (ADR 0005).
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {

    /** 모델이 이 필드에 무엇을 넣어야 하는지. 값의 제약도 여기 적는다. */
    String description();

    /** false 면 스키마의 {@code required} 에서 빠진다. */
    boolean required() default true;
}
