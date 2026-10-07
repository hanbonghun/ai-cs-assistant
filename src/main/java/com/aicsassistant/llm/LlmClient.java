package com.aicsassistant.llm;

import java.util.List;

public interface LlmClient {

    String complete(String prompt);

    String complete(List<ChatMessage> messages);

    LlmResponse completeWithUsage(List<ChatMessage> messages);

    String modelName();
}
