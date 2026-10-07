package com.aicsassistant.inquiry.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicsassistant.common.exception.GlobalExceptionHandler;
import com.aicsassistant.inquiry.application.InquiryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InquiryMessageController.class)
@Import(GlobalExceptionHandler.class)
class InquiryMessageControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InquiryService inquiryService;

    @Test
    void acceptsCustomerReply() throws Exception {
        mockMvc.perform(post("/api/inquiries/1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"ORD-20260410-001 입니다"}
                                """))
                .andExpect(status().isAccepted());

        verify(inquiryService).replyAsCustomer(1L, "ORD-20260410-001 입니다");
    }

    @Test
    void rejectsBlankReply() throws Exception {
        mockMvc.perform(post("/api/inquiries/1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(inquiryService, never()).replyAsCustomer(anyLong(), any());
    }
}
