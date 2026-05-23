package com.aicsassistant.ui.application;

import com.aicsassistant.analysis.agent.AgentStep;
import com.aicsassistant.analysis.application.AnalysisLogService;
import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.ui.viewmodel.InquiryDetailViewModel;
import com.aicsassistant.ui.viewmodel.InquiryDetailViewModel.AgentStepView;
import com.aicsassistant.ui.viewmodel.InquiryDetailViewModel.AgentStepView.DocRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 상담사 어드민 문의 상세 뷰에서 필요한 부수 데이터를 조립한다.
 *
 * <p>JSON 직렬화된 agent step 파싱, 200자 요약, 참조 문서 dedup, retrieved_chunk_ids CSV 파싱 등
 * 컨트롤러에 잡식되어 있던 비즈니스 로직을 한 곳에 모아 단위 테스트 가능하게 분리했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryDetailAssembler {

    private static final int OBSERVATION_SUMMARY_MAX_LENGTH = 200;

    private final AnalysisLogService analysisLogService;
    private final ManualService manualService;
    private final ObjectMapper objectMapper;

    public List<AgentStepView> loadAgentSteps(Long inquiryId) {
        String stepsJson = analysisLogService.getLatestAgentStepsJson(inquiryId).orElse(null);
        if (stepsJson == null || stepsJson.isBlank()) {
            return List.of();
        }
        try {
            List<AgentStep> steps = objectMapper.readValue(stepsJson, new TypeReference<>() {});
            return steps.stream().map(this::toStepView).toList();
        } catch (Exception e) {
            log.warn("Failed to deserialize agent steps for inquiryId={}: {}", inquiryId, e.getMessage());
            return List.of();
        }
    }

    public List<InquiryDetailViewModel.EvidenceChunkView> loadEvidenceChunks(Long inquiryId) {
        String retrievedChunkIds = analysisLogService.getLatestRetrievedChunkIds(inquiryId).orElse(null);
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
        return manualService.getEvidenceChunks(chunkIds);
    }

    private AgentStepView toStepView(AgentStep step) {
        String label = switch (step.action()) {
            case "search_manual"      -> "정책 문서 검색";
            case "check_order_status" -> "주문 조회";
            default                   -> step.action();
        };

        String summary = truncate(humanizeObservation(step.observation()));

        List<DocRef> docs = step.referencedChunks() == null ? List.of() :
                step.referencedChunks().stream()
                        .map(c -> new DocRef(c.manualDocumentId(), c.manualDocumentTitle(), c.manualCategory()))
                        // 같은 문서가 여러 청크로 나올 수 있어 docId 기준 dedup
                        .collect(Collectors.collectingAndThen(
                                Collectors.toMap(DocRef::docId, d -> d, (a, b) -> a, LinkedHashMap::new),
                                m -> List.copyOf(m.values())));

        return new AgentStepView(label, step.thought(), summary, docs);
    }

    /**
     * ToolResult JSON envelope에서 사람이 볼 부분만 추출한다.
     *
     * <pre>{ok:true, data:"...", errorCategory:null, isRetryable:false, errorMessage:null}</pre>
     *
     * <p>상담사는 envelope 메타필드를 볼 이유가 없다. 성공 시 {@code data} 본문만,
     * 실패 시 카테고리와 메시지만 노출한다. 파싱이 안 되면 원문을 그대로 반환한다.
     */
    private String humanizeObservation(String observation) {
        if (observation == null || observation.isBlank()) {
            return observation;
        }
        try {
            var node = objectMapper.readTree(observation);
            if (!node.isObject()) {
                return observation;
            }
            if (node.path("ok").asBoolean(false)) {
                String data = node.path("data").asText("").trim();
                return data.isEmpty() ? "(결과 없음)" : data;
            }
            String category = node.path("errorCategory").asText("");
            String message = node.path("errorMessage").asText("");
            String head = category.isEmpty() ? "실패" : category;
            return message.isEmpty() ? "❌ " + head : "❌ " + head + ": " + message;
        } catch (Exception e) {
            // JSON이 아니거나 파싱 실패 — 그대로 노출
            return observation;
        }
    }

    private static String truncate(String text) {
        if (text == null) return null;
        return text.length() > OBSERVATION_SUMMARY_MAX_LENGTH
                ? text.substring(0, OBSERVATION_SUMMARY_MAX_LENGTH) + "..."
                : text;
    }
}
