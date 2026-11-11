package com.aicsassistant.analysis.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 키워드 검색용 쿼리 전처리.
 *
 * <p>pg_trgm은 한국어 형태소를 모르기 때문에 "수 있나요" / "어떻게" 같은 공통 어미가
 * 모든 문서와 trigram이 겹쳐 false-positive 점수를 부풀린다. 어미/공통 단어를
 * 제거하여 의미 토큰만 남기면 keyword 신호 품질이 크게 개선된다.
 *
 * <p>그 앞에 <b>별칭 사전</b>({@code search/aliases.tsv})으로 고객 표현을 매뉴얼 본문 용어로 바꾼다 — "무배" 는
 * 매뉴얼에 없고 "무료배송" 은 있다. trigram 은 철자가 다른 같은 뜻을 모른다. 키워드 경로에만 쓰고 임베딩 질의에는
 * 쓰지 않는다: 임베딩은 문장 전체의 뜻을 보므로 단어를 바꿔 넣으면 효과를 따로 잴 수 없다(ADR 0010 변경 4b).
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

    /** 긴 별칭부터 바꾼다 — "다른거 왔" 를 "왔" 보다 먼저. */
    private static final Map<String, String> ALIASES = loadAliases("search/aliases.tsv");

    private KoreanQueryPreprocessor() {
    }

    static String forKeywordSearch(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String normalized = applyAliases(query);
        String cleaned = Arrays.stream(normalized.split("\\s+"))
                .filter(token -> token.length() >= 2)
                .filter(token -> !STOP_TOKENS.contains(token))
                .collect(Collectors.joining(" "));
        return cleaned.isBlank() ? normalized : cleaned;
    }

    /** 별칭을 정식 용어로 바꾼다. 질의에 정식 용어가 이미 있으면 그 별칭은 건너뛴다("재입고 알림 알림" 방지). */
    static String applyAliases(String query) {
        String out = query;
        for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
            if (out.contains(alias.getKey()) && !out.contains(alias.getValue())) {
                out = out.replace(alias.getKey(), alias.getValue());
            }
        }
        return out;
    }

    private static Map<String, String> loadAliases(String resource) {
        try (InputStream in = KoreanQueryPreprocessor.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return Map.of();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("\t"))
                    .filter(cols -> cols.length >= 2)
                    .sorted(Comparator.comparingInt((String[] cols) -> cols[0].length()).reversed())
                    .collect(Collectors.toMap(cols -> cols[0], cols -> cols[1], (a, b) -> a, LinkedHashMap::new));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + resource, e);
        }
    }
}
