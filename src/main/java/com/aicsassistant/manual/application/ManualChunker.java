package com.aicsassistant.manual.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 정책 문서를 구조 경계(문단·목록·표)를 지키며 청크로 나눈다.
 *
 * <p>이전에는 500자 고정 길이로 100자씩 겹쳐 잘랐다. 시드 매뉴얼은 문단 하나가 주제 하나인데, 고정 길이로 자르면
 * 한 청크에 주제 두세 개가 섞여 임베딩이 흐려지고, 문단 중간에서 잘린 청크가 생긴다. 실측 평가에서 관련·무관 질의의
 * 유사도 분포가 겹친 원인으로 이것을 의심했다(ADR 0010 변경 4).
 *
 * <ul>
 *   <li><b>문단</b> — 빈 줄로 나눈 블록 하나가 청크 하나다. {@code maxChars} 를 넘으면 문장 경계에서 나누고,
 *       문장 하나가 그보다 길면 그때만 길이로 자른다</li>
 *   <li><b>목록</b> — {@code -} · {@code *} · {@code 1.} · {@code 1)} 로 시작하는 줄의 묶음. 항목 경계에서만 나눈다</li>
 *   <li><b>표</b> — {@code |} 로 시작하는 줄의 묶음. 행 하나가 청크 하나이고, 행마다 머리글을 붙여
 *       "항목: 값" 으로 펼친다. 표를 줄글로 넣으면 어느 숫자가 어느 항목인지가 사라진다</li>
 *   <li><b>제목</b> — {@code #} 로 시작하는 줄은 뒤따르는 청크들 앞에 붙는다</li>
 * </ul>
 *
 * <p>겹침은 두지 않는다. 경계가 의미 단위라 문장이 잘리지 않으므로, 겹침이 막던 "경계에서 잘린 문장" 이 없다.
 */
@Component
public class ManualChunker {

    private static final Pattern BLOCK_SEPARATOR = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*([-*•]|\\d+[.)])\\s+.*");
    private static final Pattern TABLE_SEPARATOR_ROW = Pattern.compile("^\\|?\\s*:?-{2,}.*");

    private final int maxChars;

    public ManualChunker() {
        this(500);
    }

    public ManualChunker(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.maxChars = maxChars;
    }

    public List<String> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        String heading = "";
        for (String block : BLOCK_SEPARATOR.split(content.strip())) {
            List<String> lines = Arrays.stream(block.split("\\n")).map(String::strip).filter(l -> !l.isEmpty()).toList();
            if (lines.isEmpty()) {
                continue;
            }
            if (lines.size() == 1 && lines.get(0).startsWith("#")) {
                heading = lines.get(0).replaceFirst("^#+\\s*", "");
                continue;
            }
            String prefix = heading.isEmpty() ? "" : heading + "\n";
            for (String piece : splitBlock(lines)) {
                chunks.add(prefix + piece);
            }
        }
        return chunks;
    }

    private List<String> splitBlock(List<String> lines) {
        int tableStart = firstTableLine(lines);
        if (tableStart >= 0) {
            String caption = String.join(" ", lines.subList(0, tableStart));
            return tableRows(caption, lines.subList(tableStart, lines.size()));
        }
        if (lines.stream().allMatch(l -> LIST_ITEM.matcher(l).matches())) {
            return pack(lines, "\n");
        }
        String paragraph = String.join(" ", lines);
        if (paragraph.length() <= maxChars) {
            return List.of(paragraph);
        }
        return pack(Arrays.asList(SENTENCE_END.split(paragraph)), " ");
    }

    /** 표가 시작되는 줄. 머리글 행 + 구분 행 + 데이터 행이 있어야 표로 본다. */
    private static int firstTableLine(List<String> lines) {
        for (int i = 0; i + 2 < lines.size(); i++) {
            if (lines.get(i).startsWith("|") && TABLE_SEPARATOR_ROW.matcher(lines.get(i + 1)).matches()) {
                return i;
            }
        }
        return -1;
    }

    private List<String> tableRows(String caption, List<String> tableLines) {
        List<String> header = cells(tableLines.get(0));
        List<String> rows = new ArrayList<>();
        for (String line : tableLines.subList(2, tableLines.size())) {
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> values = cells(line);
            List<String> pairs = new ArrayList<>();
            for (int c = 0; c < values.size(); c++) {
                String name = c < header.size() ? header.get(c) : "";
                pairs.add(name.isEmpty() ? values.get(c) : name + ": " + values.get(c));
            }
            String row = String.join(" · ", pairs);
            rows.addAll(hardSplit(caption.isEmpty() ? row : caption + "\n" + row));
        }
        return rows;
    }

    private static List<String> cells(String row) {
        String trimmed = row.strip().replaceAll("^\\|", "").replaceAll("\\|$", "");
        return Arrays.stream(trimmed.split("\\|")).map(String::strip).toList();
    }

    /** 단위(문장·목록 항목)를 순서대로 담되 {@code maxChars} 를 넘기 전에 끊는다. */
    private List<String> pack(List<String> units, String joiner) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (!current.isEmpty() && current.length() + joiner.length() + unit.length() > maxChars) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (unit.length() > maxChars) {
                out.addAll(hardSplit(unit));
                continue;
            }
            current.append(current.isEmpty() ? "" : joiner).append(unit);
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    /** 구조 경계가 없는 긴 텍스트만 길이로 자른다. */
    private List<String> hardSplit(String text) {
        List<String> out = new ArrayList<>();
        for (int start = 0; start < text.length(); start += maxChars) {
            out.add(text.substring(start, Math.min(text.length(), start + maxChars)).strip());
        }
        return out;
    }
}
