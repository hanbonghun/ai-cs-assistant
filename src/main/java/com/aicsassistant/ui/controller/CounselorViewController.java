package com.aicsassistant.ui.controller;

import com.aicsassistant.analysis.agent.AgentStep;
import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.inquiry.application.InquiryService;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import java.util.LinkedHashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/ui")
public class CounselorViewController {

    private final InquiryService inquiryService;
    private final ManualService manualService;
    private final InquiryAnalysisLogRepository inquiryAnalysisLogRepository;
    private final InquiryMessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public CounselorViewController(
            InquiryService inquiryService,
            ManualService manualService,
            InquiryAnalysisLogRepository inquiryAnalysisLogRepository,
            InquiryMessageRepository messageRepository,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.inquiryService = inquiryService;
        this.manualService = manualService;
        this.inquiryAnalysisLogRepository = inquiryAnalysisLogRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

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
