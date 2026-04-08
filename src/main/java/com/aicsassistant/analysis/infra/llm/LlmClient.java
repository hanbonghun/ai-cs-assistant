package com.aicsassistant.analysis.infra.llm;

public interface LlmClient {

    String complete(String prompt);

    String modelName();
}
