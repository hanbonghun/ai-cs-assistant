package com.aicsassistant.analysis.infra.llm;

import java.util.List;

public interface EmbeddingClient {

    List<Double> embed(String text);

    /**
     * 여러 텍스트를 묶어 임베딩한다. <b>반환 순서는 입력 순서와 같다.</b>
     *
     * <p>{@code default} 로 둔 이유는 두 가지다. 한 건씩 도는 이 구현이 그 자체로 올바른 폴백이고,
     * 추상 메서드를 하나로 유지해야 테스트의 페이크가 람다 한 줄로 남는다. 실제 왕복 절감은
     * {@link OpenAiClient} 의 재정의가 한다.
     */
    default List<List<Double>> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
