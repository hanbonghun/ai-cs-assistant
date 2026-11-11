package com.aicsassistant.analysis.agent.tool;

import com.aicsassistant.analysis.agent.AgentTool;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolParam;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.analysis.application.ManualRetrievalService;
import com.aicsassistant.analysis.application.PromptFactory;
import com.aicsassistant.analysis.dto.RetrievedManualChunkDto;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 정책/매뉴얼 벡터 스토어에서 RAG 검색을 수행하는 도구.
 * 에이전트 실행마다 새 인스턴스로 생성되어 누적 청크 상태가 격리된다.
 */
public class SearchManualTool implements AgentTool<SearchManualTool.Input> {

    /** 도구 입력 — 단일 한국어 검색 쿼리. */
    public record Input(
            @ToolParam(description = "Korean keywords describing the policy you need "
                    + "(e.g. '환불 가능 기간', '교환 배송비')")
            String query
    ) {}

    private final ManualRetrievalService manualRetrievalService;
    private final List<RetrievedManualChunkDto> collectedChunks = new ArrayList<>();
    private List<RetrievedManualChunkDto> lastCallChunks = List.of();

    public SearchManualTool(ManualRetrievalService manualRetrievalService) {
        this.manualRetrievalService = manualRetrievalService;
    }

    @Override
    public String name() {
        return "search_manual";
    }

    @Override
    public String description() {
        return "Searches the policy/manual knowledge base via vector similarity.";
    }

    @Override
    public String whenToUse() {
        return "Call before answering ANY policy or process question (refund, return, delivery rules, coupons, membership). "
                + "Do not answer policy from your own knowledge.";
    }

    @Override
    public String usageBoundary() {
        return "Do NOT use for: (1) order-specific status/tracking/amount lookups (use check_order_status), "
                + "(2) customer personal info. This tool returns authoritative policy chunks — it is the only "
                + "source for policy answers, so do not answer policy from your own knowledge.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public String successOutputHint() {
        return "Policy chunks, each wrapped as '<<<REFERENCE_DOCUMENT>>>\\n[<title> / <category>]\\n<content>\\n<<<END_REFERENCE_DOCUMENT>>>'. "
                + "If no chunks match the query, data is the literal string 'No relevant policy documents found for this query.' (this is still ok=true).";
    }

    @Override
    public String failureBehavior() {
        return "VALIDATION (empty query): refine the query into Korean policy keywords and retry. "
                + "TRANSIENT (vector store error): retry once; if it fails again, summarize and set needsHumanReview: true.";
    }

    @Override
    public ToolResult execute(Input input) {
        String query = input.query() == null ? "" : input.query().strip();
        if (query.isBlank()) {
            return ToolResult.error(
                    ToolErrorCategory.VALIDATION,
                    false,
                    "'query' field is required.");
        }

        List<RetrievedManualChunkDto> chunks = manualRetrievalService.retrieve(query);
        lastCallChunks = List.copyOf(chunks);
        collectedChunks.addAll(chunks);

        if (chunks.isEmpty()) {
            return ToolResult.success("No relevant policy documents found for this query.");
        }

        // 청크마다 울타리를 친다 — 본문에 심긴 지시가 Observation(신뢰 채널) 안에서 지시로 읽히지 않게 (ADR 0012)
        String formatted = chunks.stream()
                .map(c -> PromptFactory.fenceReferenceDocument(
                        c.manualDocumentTitle(), c.manualCategory(), c.content()))
                .collect(Collectors.joining("\n\n"));
        return ToolResult.success(formatted);
    }

    /**
     * 상담사 전용 문서에서 참고 근거를 찾는다. 결과는 모델에게 가지 않는다 — 상담사에게 넘어간 건의 화면에만 붙는다.
     */
    public List<RetrievedManualChunkDto> counselorReferences(String inquiryContent) {
        return manualRetrievalService.retrieveCounselorReferences(inquiryContent);
    }

    /** Returns all chunks retrieved across every call during this agent run. */
    public List<RetrievedManualChunkDto> getCollectedChunks() {
        return List.copyOf(collectedChunks);
    }

    /** Returns only the chunks retrieved in the most recent call. */
    public List<RetrievedManualChunkDto> getLastCallChunks() {
        return lastCallChunks;
    }
}
