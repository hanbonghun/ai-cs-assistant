package com.aicsassistant.analysis.infra.llm;

import java.util.List;

public interface LlmClient {

    String complete(String prompt);

    String complete(List<ChatMessage> messages);

    String modelName();
}
