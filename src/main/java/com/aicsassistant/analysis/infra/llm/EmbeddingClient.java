package com.aicsassistant.analysis.infra.llm;

import java.util.List;

public interface EmbeddingClient {

    List<Double> embed(String text);
}
