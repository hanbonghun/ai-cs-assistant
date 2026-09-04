package com.aicsassistant.analysis.domain;

public enum AnalysisStatus {
    /** 분석 시작됨. 종료 시 SUCCESS 또는 FAILURE 로 제자리에서 뒤집힌다. */
    RUNNING,
    SUCCESS,
    FAILURE
}
