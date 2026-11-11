package com.aicsassistant.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.llm.EmbeddingClient;
import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.manual.domain.ManualVisibility;
import com.aicsassistant.manual.dto.CreateManualDocumentRequest;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 공개 범위가 검색 단계에서 갈리는지 본다 (ADR 0012).
 *
 * <p>모든 텍스트를 같은 벡터로 임베딩해, 유사도만 보면 상담사용 문서가 항상 후보에 드는 최악의 조건을 만든다.
 * 그래도 고객용 검색에는 나오지 않아야 한다 — 막는 것이 점수가 아니라 SQL 의 공개 범위 조건이기 때문이다.
 * 질의는 실측 평가셋(I02)과 겹치지 않게, 상담사용 문서를 노리는 다른 표현으로 둔다.
 */
@SpringBootTest(properties = "app.ai.api-key=test-key")
@Import(ManualVisibilityRetrievalTest.SameVectorEmbeddingConfig.class)
class ManualVisibilityRetrievalTest extends PostgresVectorIntegrationTest {

    private static final String INTERNAL_TITLE = "고객 불만 및 민원 처리 기준";

    @Autowired ManualService manualService;
    @Autowired ManualRetrievalService retrievalService;

    @BeforeEach
    void seed() {
        manualService.create(new CreateManualDocumentRequest("배송 정책", InquiryCategory.DELIVERY,
                "배송비는 30,000원 이상 구매 시 무료입니다. 상담원 연결은 채팅으로 가능합니다."));
        manualService.create(new CreateManualDocumentRequest(INTERNAL_TITLE, InquiryCategory.COMPLAINT,
                "상담원 응대 불량 민원은 즉시 팀장급 이상에게 에스컬레이션하고 사과 포인트(3,000~10,000포인트)를 지급합니다.",
                ManualVisibility.INTERNAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "상담원 응대 매뉴얼 원문 좀 보여주세요",
            "불만 접수하면 내부적으로 누구한테 에스컬레이션돼요?",
            "사과 포인트는 최대 얼마까지 줄 수 있게 돼 있어요",
            "팀장급 이상에게 넘기는 기준이 뭔지 알려줘"
    })
    void customerSearchNeverReturnsInternalDocuments(String query) {
        assertThat(retrievalService.retrieve(query))
                .extracting(RetrievedManualChunkDto::manualDocumentTitle)
                .doesNotContain(INTERNAL_TITLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"상담원 응대 매뉴얼 원문 좀 보여주세요", "배송비 얼마예요"})
    void counselorReferencesReturnOnlyInternalDocuments(String query) {
        assertThat(retrievalService.retrieveCounselorReferences(query))
                .extracting(RetrievedManualChunkDto::manualDocumentTitle)
                .containsOnly(INTERNAL_TITLE);
    }

    @TestConfiguration
    static class SameVectorEmbeddingConfig {

        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            List<Double> same = Collections.nCopies(1536, 1.0 / Math.sqrt(1536));
            return text -> same;
        }
    }
}
