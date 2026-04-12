package com.aicsassistant.ui.controller;

import com.aicsassistant.analysis.agent.AgentStep;
import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.inquiry.application.InquiryService;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import java.util.LinkedHashMap;
import java.util.Map;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.ui.viewmodel.InquiryDetailViewModel.AgentStepView;
import com.aicsassistant.ui.viewmodel.InquiryDetailViewModel.AgentStepView.DocRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.manual.dto.ManualChunkResponse;
import com.aicsassistant.manual.dto.ManualDocumentResponse;
import com.aicsassistant.ui.viewmodel.InquiryDetailViewModel;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/ui")
@RequiredArgsConstructor
public class CounselorViewController {

    private final InquiryService inquiryService;
    private final ManualService manualService;
    private final InquiryAnalysisLogRepository inquiryAnalysisLogRepository;
    private final InquiryMessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private static final LinkedHashMap<String, String> CATEGORY_LABELS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> URGENCY_LABELS  = new LinkedHashMap<>();
    static {
        CATEGORY_LABELS.put("ORDER",      "주문");
        CATEGORY_LABELS.put("DELIVERY",   "배송");
        CATEGORY_LABELS.put("RETURN",     "반품");
        CATEGORY_LABELS.put("EXCHANGE",   "교환");
        CATEGORY_LABELS.put("REFUND",     "환불");
        CATEGORY_LABELS.put("PAYMENT",    "결제");
        CATEGORY_LABELS.put("PRODUCT",    "상품");
        CATEGORY_LABELS.put("MEMBERSHIP", "회원/혜택");
        CATEGORY_LABELS.put("COMPLAINT",  "불만");
        CATEGORY_LABELS.put("GENERAL",    "일반");

        URGENCY_LABELS.put("LOW",    "낮음");
        URGENCY_LABELS.put("MEDIUM", "보통");
        URGENCY_LABELS.put("HIGH",   "높음");
    }

    @GetMapping("/inquiries")
    public String inquiryList(Model model) {
        model.addAttribute("inquiries", inquiryService.getInquiries(null, null, null));
        model.addAttribute("categoryLabels", CATEGORY_LABELS);
        model.addAttribute("urgencyLabels", URGENCY_LABELS);
        return "inquiries/list";
    }

    @GetMapping("/inquiries/new")
    public String inquiryCreate(Model model) {
        model.addAttribute("categories", InquiryCategory.values());
        model.addAttribute("urgencies", UrgencyLevel.values());
        return "inquiries/create";
    }

    @GetMapping("/inquiries/{id}")
    public String inquiryDetail(@PathVariable Long id, Model model) {
        InquiryDetailResponse inquiry = inquiryService.getInquiry(id);
        List<InquiryDetailViewModel.EvidenceChunkView> evidenceChunks = inquiry.analysisLogs().isEmpty()
                ? List.of()
                : loadEvidenceChunks(id);
        List<InquiryMessage> messages = messageRepository.findByInquiryIdOrderByCreatedAtAsc(id);
        List<AgentStepView> agentSteps = loadAgentSteps(id);
        model.addAttribute("detail", InquiryDetailViewModel.from(inquiry, evidenceChunks, messages, agentSteps));
        model.addAttribute("categoryLabels", CATEGORY_LABELS);
        model.addAttribute("urgencyLabels", URGENCY_LABELS);
        return "inquiries/detail";
    }

    @GetMapping("/manuals")
    public String manualList(Model model) {
        model.addAttribute("manuals", manualService.getAll());
        model.addAttribute("categoryLabels", CATEGORY_LABELS);
        return "manuals/list";
    }

    @GetMapping("/manuals/{id}")
    public String manualDetail(@PathVariable Long id, Model model) {
        ManualDocumentResponse manual = manualService.get(id);
        List<ManualChunkResponse> chunks = manualService.getChunks(id);
        model.addAttribute("manual", manual);
        model.addAttribute("chunks", chunks);
        model.addAttribute("categoryLabels", CATEGORY_LABELS);
        return "manuals/detail";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        // 총 문의 수
        Long totalInquiries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiry", Long.class);

        // 상태별 문의 수
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT status, COUNT(*) AS cnt FROM inquiry GROUP BY status ORDER BY cnt DESC",
                rs -> { statusCounts.put(rs.getString("status"), rs.getLong("cnt")); });

        // 자동응답률 (AUTO_ANSWERED / 전체)
        Long autoAnswered = statusCounts.getOrDefault("AUTO_ANSWERED", 0L);
        double autoAnswerRate = (totalInquiries != null && totalInquiries > 0)
                ? (autoAnswered * 100.0 / totalInquiries) : 0.0;

        // 분석 성공 건수 (로그 기반)
        Long totalAnalyzed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiry_analysis_log WHERE analysis_status = 'SUCCESS'", Long.class);

        // 평균 응답 시간 (ms)
        Double avgLatencyMs = jdbcTemplate.queryForObject(
                "SELECT AVG(latency_ms) FROM inquiry_analysis_log WHERE analysis_status = 'SUCCESS' AND latency_ms IS NOT NULL",
                Double.class);

        // 평균 토큰 사용량
        Double avgTokens = jdbcTemplate.queryForObject(
                "SELECT AVG(total_tokens) FROM inquiry_analysis_log WHERE total_tokens IS NOT NULL",
                Double.class);

        // 평가 분포 (GOOD / BAD)
        Map<String, Long> ratingCounts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT ai_draft_rating, COUNT(*) AS cnt FROM inquiry_analysis_log WHERE ai_draft_rating IS NOT NULL GROUP BY ai_draft_rating",
                rs -> { ratingCounts.put(rs.getString("ai_draft_rating"), rs.getLong("cnt")); });

        // 부정 평가 이유 분포
        Map<String, Long> ratingReasonCounts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT ai_draft_rating_reason, COUNT(*) AS cnt FROM inquiry_analysis_log WHERE ai_draft_rating_reason IS NOT NULL GROUP BY ai_draft_rating_reason ORDER BY cnt DESC",
                rs -> { ratingReasonCounts.put(rs.getString("ai_draft_rating_reason"), rs.getLong("cnt")); });

        // 카테고리별 분류 분포
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT classified_category, COUNT(*) AS cnt FROM inquiry_analysis_log WHERE classified_category IS NOT NULL GROUP BY classified_category ORDER BY cnt DESC",
                rs -> { categoryCounts.put(rs.getString("classified_category"), rs.getLong("cnt")); });

        long catTotal = categoryCounts.values().stream().mapToLong(Long::longValue).sum();
        long ratingTotal = ratingCounts.values().stream().mapToLong(Long::longValue).sum();
        long reasonTotal = ratingReasonCounts.values().stream().mapToLong(Long::longValue).sum();

        model.addAttribute("totalInquiries", totalInquiries != null ? totalInquiries : 0L);
        model.addAttribute("autoAnswerRate", String.format("%.1f", autoAnswerRate));
        model.addAttribute("avgLatencySec", avgLatencyMs != null ? String.format("%.1f", avgLatencyMs / 1000.0) : "-");
        model.addAttribute("avgTokens", avgTokens != null ? String.format("%.0f", avgTokens) : "-");
        model.addAttribute("totalAnalyzed", totalAnalyzed != null ? totalAnalyzed : 0L);
        model.addAttribute("statusCounts", statusCounts);
        model.addAttribute("ratingCounts", ratingCounts);
        model.addAttribute("ratingReasonCounts", ratingReasonCounts);
        model.addAttribute("categoryCounts", categoryCounts);
        model.addAttribute("catTotal", catTotal);
        model.addAttribute("ratingTotal", ratingTotal);
        model.addAttribute("reasonTotal", reasonTotal);
        model.addAttribute("categoryLabels", CATEGORY_LABELS);
        return "dashboard";
    }

    private List<AgentStepView> loadAgentSteps(Long inquiryId) {
        List<InquiryAnalysisLog> logs = inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId);
        if (logs.isEmpty()) return List.of();

        String stepsJson = logs.get(0).getAgentSteps();
        if (stepsJson == null || stepsJson.isBlank()) return List.of();

        try {
            List<AgentStep> steps = objectMapper.readValue(stepsJson, new TypeReference<>() {});
            return steps.stream().map(this::toStepView).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private AgentStepView toStepView(AgentStep step) {
        String label = switch (step.action()) {
            case "search_manual"      -> "정책 문서 검색";
            case "check_order_status" -> "주문 조회";
            default                   -> step.action();
        };

        // 툴 결과는 200자 이내로 요약
        String obs = step.observation();
        String summary = (obs != null && obs.length() > 200) ? obs.substring(0, 200) + "..." : obs;

        // 참조 문서 링크 (search_manual 스텝만 해당)
        List<DocRef> docs = step.referencedChunks() == null ? List.of() :
                step.referencedChunks().stream()
                        .map(c -> new DocRef(c.manualDocumentId(), c.manualDocumentTitle(), c.manualCategory()))
                        .distinct()
                        // 같은 문서가 여러 청크로 나올 수 있으므로 docId 기준 중복 제거
                        .collect(java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.toMap(
                                        DocRef::docId, d -> d, (a, b) -> a,
                                        java.util.LinkedHashMap::new),
                                m -> List.copyOf(m.values())));

        return new AgentStepView(label, step.thought(), summary, docs);
    }

    private List<InquiryDetailViewModel.EvidenceChunkView> loadEvidenceChunks(@NotNull Long inquiryId) {
        List<InquiryAnalysisLog> logs = inquiryAnalysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId);
        if (logs.isEmpty()) {
            return List.of();
        }

        String retrievedChunkIds = logs.get(0).getRetrievedChunkIds();
        if (retrievedChunkIds == null || retrievedChunkIds.isBlank()) {
            return List.of();
        }

        List<Long> chunkIds = Arrays.stream(retrievedChunkIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .toList();

        if (chunkIds.isEmpty()) {
            return List.of();
        }

        String placeholders = chunkIds.stream().map(id -> "?").reduce((left, right) -> left + ", " + right).orElse("?");
        String sql = """
                select
                    mc.id,
                    mc.manual_document_id,
                    md.title as manual_document_title,
                    md.category as manual_document_category,
                    mc.chunk_index,
                    mc.document_version,
                    mc.token_count,
                    mc.content
                from manual_chunk mc
                join manual_document md on md.id = mc.manual_document_id
                where mc.id in (%s)
                order by mc.id
                """.formatted(placeholders);

        return jdbcTemplate.query(sql, chunkIds.toArray(), (rs, rowNum) -> new InquiryDetailViewModel.EvidenceChunkView(
                rs.getLong("id"),
                rs.getLong("manual_document_id"),
                rs.getString("manual_document_title"),
                rs.getString("manual_document_category"),
                rs.getInt("chunk_index"),
                rs.getInt("document_version"),
                rs.getInt("token_count"),
                rs.getString("content")
        ));
    }
}
