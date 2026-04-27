package com.aicsassistant.analysis.agent.tool;

import com.aicsassistant.analysis.agent.AgentTool;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.faq.InMemoryFaqRepository;
import java.util.Optional;

/**
 * 큐레이션된 자주 묻는 질문(FAQ) 단답 검색 도구.
 *
 * <p>{@link SearchManualTool}과 의도적으로 기능이 겹친다. 모델이 도구 설명을 보고
 * "긴 정책 원문이 필요한가? 짧은 즉답이 충분한가?"를 구분하는지 검증하기 위함.
 */
public class SearchFaqTool implements AgentTool<SearchFaqTool.Input> {

    /** 도구 입력 — 한 문장의 자연어 질문. */
    public record Input(String question) {}

    private final InMemoryFaqRepository faqRepository;

    public SearchFaqTool(InMemoryFaqRepository faqRepository) {
        this.faqRepository = faqRepository;
    }

    @Override
    public String name() {
        return "search_faq";
    }

    @Override
    public String description() {
        return "Returns a single curated short answer to a frequently-asked customer question.";
    }

    @Override
    public String whenToUse() {
        return "Call FIRST for short, common, well-known questions where a one-paragraph answer is enough "
                + "(e.g. '환불 며칠 걸려요?', '배송 조회는 어디?', '회원 탈퇴는?', '쿠폰 확인'). "
                + "Cheaper and more direct than search_manual for these cases.";
    }

    @Override
    public String usageBoundary() {
        return "Do NOT use for: (1) questions requiring exact policy clauses or legal-style detail "
                + "(use search_manual instead — its chunks are longer and authoritative), "
                + "(2) order-specific data (use check_order_status), "
                + "(3) novel/edge-case questions unlikely to be in a FAQ list. "
                + "If this tool returns NOT_FOUND, fall back to search_manual.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public String inputSchema() {
        return "{\"question\": \"string (required) — single Korean question, ideally short (e.g. '환불은 며칠 걸리나요?')\"}";
    }

    @Override
    public String successOutputHint() {
        return "A short single-paragraph answer text (not a chunk list). Suitable for direct customer reply with light formatting.";
    }

    @Override
    public String failureBehavior() {
        return "VALIDATION (empty question): rephrase the customer's request as a short question and retry. "
                + "NOT_FOUND (no matching FAQ): do NOT retry the same question — call search_manual instead for a more detailed answer.";
    }

    @Override
    public ToolResult execute(Input input) {
        String question = input.question() == null ? "" : input.question().strip();
        if (question.isBlank()) {
            return ToolResult.error(
                    ToolErrorCategory.VALIDATION,
                    false,
                    "'question' field is required.");
        }
        Optional<InMemoryFaqRepository.FaqEntry> match = faqRepository.findBest(question);
        if (match.isEmpty()) {
            return ToolResult.error(
                    ToolErrorCategory.NOT_FOUND,
                    false,
                    "FAQ에서 일치하는 항목을 찾지 못했습니다. 더 자세한 정책 정보를 위해 search_manual을 호출하세요.");
        }
        InMemoryFaqRepository.FaqEntry entry = match.get();
        return ToolResult.success("Q: " + entry.question() + "\nA: " + entry.answer());
    }
}
