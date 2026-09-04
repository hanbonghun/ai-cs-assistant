package com.aicsassistant.ui.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicsassistant.inquiry.application.InquiryService;
import com.aicsassistant.order.InMemoryOrderRepository;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 유저 포털 화면이 실제로 렌더되는지 검증한다.
 *
 * <p>이 테스트가 있는 이유: 사용자 신원({@code DummyUserStore})과 주문 상세
 * ({@code InMemoryOrderRepository})를 화면 직전에 합치도록 바꾸면서 모델에 넣는 객체 타입이
 * 바뀌었다. Thymeleaf 표현식은 컴파일러가 잡지 못해 브라우저에서만 터지고, 실제로 같은 종류의
 * 결함이 어드민 목록에서 프로덕션 500 을 냈다. 렌더가 유일한 검사 표면이다.
 *
 * <p>실제 {@code InMemoryOrderRepository} 를 쓴다 — 조인 결과가 템플릿이 기대하는 필드를
 * 갖는지가 검증 대상이므로 mock 으로 대체하면 의미가 없다.
 */
@WebMvcTest(UserViewController.class)
@Import(InMemoryOrderRepository.class)
public class UserViewControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InquiryService inquiryService;

    private HttpSession loggedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", "cust-001");
        return session;
    }

    @Test
    public void rendersUserSelectWithOrderCounts() throws Exception {
        mvc.perform(get("/app"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("김민준")))
                .andExpect(content().string(containsString("6개 주문")));
    }

    @Test
    public void rendersHomeWithJoinedOrderDetails() throws Exception {
        given(inquiryService.getInquiriesByCustomer("cust-001")).willReturn(List.of());

        mvc.perform(get("/app/home").session((MockHttpSession) loggedIn()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ORD-20260410-001")))
                .andExpect(content().string(containsString("무선 블루투스 이어폰 AX-300")))
                .andExpect(content().string(containsString("89,000원")));
    }

    @Test
    public void rendersInquiryFormWithOrderOptions() throws Exception {
        mvc.perform(get("/app/inquiries/new").session((MockHttpSession) loggedIn()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ORD-20260410-001")))
                .andExpect(content().string(containsString("무선 블루투스 이어폰 AX-300")));
    }
}
