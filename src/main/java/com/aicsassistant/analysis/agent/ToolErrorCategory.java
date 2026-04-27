package com.aicsassistant.analysis.agent;

/**
 * 도구 실행 실패 유형. LLM이 다음 행동(재시도/입력수정/에스컬레이션)을 결정하는 단서로 사용한다.
 */
public enum ToolErrorCategory {
    /** 일시적 외부 장애. 짧은 시간 후 같은 입력으로 재시도 가능. */
    TRANSIENT,
    /** 입력 형식/필수값 오류. LLM이 actionInput을 수정해 다시 호출해야 함. */
    VALIDATION,
    /** 권한 부족. 자동 재시도 금지. 상담사 에스컬레이션 신호로 사용. */
    PERMISSION,
    /** 요청한 리소스가 존재하지 않음. 같은 입력으로 재시도해도 결과 동일. */
    NOT_FOUND
}
