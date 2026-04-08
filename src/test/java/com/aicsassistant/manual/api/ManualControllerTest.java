package com.aicsassistant.manual.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.common.exception.GlobalExceptionHandler;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.manual.application.ManualService;
import com.aicsassistant.manual.dto.ManualChunkResponse;
import com.aicsassistant.manual.dto.ManualDocumentResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ManualController.class)
@Import(GlobalExceptionHandler.class)
class ManualControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ManualService manualService;

    @Test
    void createsManualDocument() throws Exception {
        given(manualService.create(eq(new com.aicsassistant.manual.dto.CreateManualDocumentRequest(
                "환불 안내",
                InquiryCategory.REFUND,
                "환불은 영업일 기준 3일 내 처리됩니다."
        )))).willReturn(new ManualDocumentResponse(
                1L,
                "환불 안내",
                InquiryCategory.REFUND,
                "환불은 영업일 기준 3일 내 처리됩니다.",
                1,
                true,
                LocalDateTime.of(2026, 4, 8, 10, 0),
                LocalDateTime.of(2026, 4, 8, 10, 0)
        ));

        mockMvc.perform(post("/api/manual-documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "환불 안내",
                                  "category": "REFUND",
                                  "content": "환불은 영업일 기준 3일 내 처리됩니다."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void returnsActiveChunksForManualDocument() throws Exception {
        given(manualService.getChunks(1L)).willReturn(List.of(
                new ManualChunkResponse(
                        10L,
                        1L,
                        0,
                        1,
                        "첫 번째 청크",
                        2,
                        true,
                        LocalDateTime.of(2026, 4, 8, 10, 0)
                )
        ));

        mockMvc.perform(get("/api/manual-documents/1/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].manualDocumentId").value(1))
                .andExpect(jsonPath("$[0].chunkIndex").value(0))
                .andExpect(jsonPath("$[0].content").value("첫 번째 청크"));
    }

    @Test
    void deletesManualDocumentWithSoftDelete() throws Exception {
        mockMvc.perform(delete("/api/manual-documents/1"))
                .andExpect(status().isNoContent());

        verify(manualService).delete(1L);
    }

    @Test
    void returnsNotFoundWhenManualDocumentDoesNotExist() throws Exception {
        given(manualService.get(99L)).willThrow(
                new ApiException(HttpStatus.NOT_FOUND, "MANUAL_DOCUMENT_NOT_FOUND", "Manual document not found")
        );

        mockMvc.perform(get("/api/manual-documents/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MANUAL_DOCUMENT_NOT_FOUND"));
    }
}
