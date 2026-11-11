package com.aicsassistant.manual.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ManualChunkerTest {

    private final ManualChunker chunker = new ManualChunker(500);

    @Test
    void eachParagraphBecomesItsOwnChunk() {
        List<String> chunks = chunker.chunk("배송비는 3,000원입니다.\n\n교환은 7일 이내 가능합니다.\n\n반품은 수령 후 7일 이내입니다.");

        assertThat(chunks).containsExactly(
                "배송비는 3,000원입니다.", "교환은 7일 이내 가능합니다.", "반품은 수령 후 7일 이내입니다.");
    }

    @Test
    void longParagraphSplitsAtSentenceBoundariesOnly() {
        String sentence = "가".repeat(180) + "입니다.";
        List<String> chunks = chunker.chunk(sentence + " " + sentence + " " + sentence);

        assertThat(chunks).hasSize(2).allSatisfy(c -> assertThat(c).endsWith("입니다.").hasSizeLessThanOrEqualTo(500));
    }

    @Test
    void textWithoutAnyBoundaryIsCutByLength() {
        assertThat(chunker.chunk("A".repeat(1200))).hasSize(3);
    }

    @Test
    void tableRowsCarryTheirHeaders() {
        String table = """
                결제 수단별 환불 소요 기간
                | 결제 수단 | 소요 기간 |
                |---|---|
                | 신용카드 | 영업일 3~5일 |
                | 무통장 입금 | 영업일 1~3일 |""";

        assertThat(chunker.chunk(table)).containsExactly(
                "결제 수단별 환불 소요 기간\n결제 수단: 신용카드 · 소요 기간: 영업일 3~5일",
                "결제 수단별 환불 소요 기간\n결제 수단: 무통장 입금 · 소요 기간: 영업일 1~3일");
    }

    @Test
    void listStaysTogetherUntilItOverflows() {
        String list = "- 미사용 상태\n- 원 포장 유지\n- 구성품 전체 포함";

        assertThat(chunker.chunk(list)).containsExactly(list);
        assertThat(new ManualChunker(20).chunk(list)).containsExactly("- 미사용 상태\n- 원 포장 유지", "- 구성품 전체 포함");
    }

    @Test
    void headingIsPrefixedToFollowingChunks() {
        List<String> chunks = chunker.chunk("# 반품 정책\n\n반품은 7일 이내입니다.\n\n하자는 30일 이내입니다.");

        assertThat(chunks).containsExactly("반품 정책\n반품은 7일 이내입니다.", "반품 정책\n하자는 30일 이내입니다.");
    }
}
