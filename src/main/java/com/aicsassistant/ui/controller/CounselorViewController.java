package com.aicsassistant.ui.controller;

import com.aicsassistant.analysis.domain.InquiryAnalysisLog;
import com.aicsassistant.analysis.infra.InquiryAnalysisLogRepository;
import com.aicsassistant.inquiry.application.InquiryService;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.UrgencyLevel;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
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
    private final JdbcTemplate jdbcTemplate;

    public CounselorViewController(
            InquiryService inquiryService,
            ManualService manualService,
            InquiryAnalysisLogRepository inquiryAnalysisLogRepository,
            InquiryMessageRepository messageRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.inquiryService = inquiryService;
        this.manualService = manualService;
        this.inquiryAnalysisLogRepository = inquiryAnalysisLogRepository;
        this.messageRepository = messageRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/inquiries")
    public String inquiryList(Model model) {
        model.addAttribute("inquiries", inquiryService.getInquiries(null, null, null));
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
        model.addAttribute("detail", InquiryDetailViewModel.from(inquiry, evidenceChunks, messages));
        return "inquiries/detail";
    }

    @GetMapping("/manuals")
    public String manualList(Model model) {
        model.addAttribute("manuals", manualService.getAll());
        model.addAttribute("categories", InquiryCategory.values());
        return "manuals/list";
    }

    @GetMapping("/manuals/{id}")
    public String manualDetail(@PathVariable Long id, Model model) {
        ManualDocumentResponse manual = manualService.get(id);
        List<ManualChunkResponse> chunks = manualService.getChunks(id);
        model.addAttribute("manual", manual);
        model.addAttribute("chunks", chunks);
        model.addAttribute("categories", InquiryCategory.values());
        return "manuals/detail";
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
