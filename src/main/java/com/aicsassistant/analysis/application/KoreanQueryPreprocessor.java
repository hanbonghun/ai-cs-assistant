package com.aicsassistant.analysis.application;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 키워드 검색용 쿼리 전처리.
 *
 * <p>pg_trgm은 한국어 형태소를 모르기 때문에 "수 있나요" / "어떻게" 같은 공통 어미가
 * 모든 문서와 trigram이 겹쳐 false-positive 점수를 부풀린다. 어미/공통 단어를
 * 제거하여 의미 토큰만 남기면 keyword 신호 품질이 크게 개선된다.
 */
final class KoreanQueryPreprocessor {

    private static final Set<String> STOP_TOKENS = Set.of(
            "수", "안", "잘", "거", "게", "더", "좀", "또", "및", "등",
            "이", "그", "저", "내", "네", "그게", "이게", "저게",
            "있나요", "되나요", "어요", "아요", "예요", "에요",
            "세요", "주세요", "드려요", "드세요", "까요",
            "어떻게", "알려줘", "알려주세요", "안내", "해줘", "해주세요",
            "가능한가요", "가능해요", "가능합니까", "되요", "됩니까", "합니까",
            "뭐예요", "돼요", "어떡해", "어떡하죠"
    );

    private KoreanQueryPreprocessor() {
    }

    static String forKeywordSearch(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String cleaned = Arrays.stream(query.split("\\s+"))
                .filter(token -> token.length() >= 2)
                .filter(token -> !STOP_TOKENS.contains(token))
                .collect(Collectors.joining(" "));
        return cleaned.isBlank() ? query : cleaned;
    }
}
