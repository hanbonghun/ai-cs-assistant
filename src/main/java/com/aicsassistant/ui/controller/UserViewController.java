package com.aicsassistant.ui.controller;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.application.InquiryService;
import com.aicsassistant.inquiry.domain.InquiryCategory;
import com.aicsassistant.inquiry.dto.InquiryDetailResponse;
import com.aicsassistant.inquiry.dto.InquiryListResponse;
import com.aicsassistant.inquiry.dto.InquiryMessageResponse;
import com.aicsassistant.ui.application.UserViewAssembler;
import com.aicsassistant.ui.viewmodel.UserView;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/app")
@RequiredArgsConstructor
public class UserViewController {

    private static final Map<String, String> CATEGORY_LABELS = InquiryCategory.labels();

    /** 주문 선택이 필요한 카테고리 */
    static final Set<InquiryCategory> ORDER_REQUIRED = Set.of(
            InquiryCategory.ORDER,
            InquiryCategory.DELIVERY,
            InquiryCategory.RETURN,
            InquiryCategory.EXCHANGE,
            InquiryCategory.REFUND,
            InquiryCategory.PAYMENT
    );

    private final InquiryService inquiryService;
    private final UserViewAssembler userViewAssembler;

    /** 사용자 선택 화면 */
    @GetMapping
    public String selectUser(Model model) {
        model.addAttribute("users", userViewAssembler.getAll());
        return "user/select";
    }

    /** 세션에 사용자 설정 후 홈으로 이동 */
    @PostMapping("/login")
    public String login(@RequestParam String userId, HttpSession session) {
        userViewAssembler.find(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_USER", "Unknown user: " + userId));
        session.setAttribute("userId", userId);
        return "redirect:/app/home";
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/app";
    }

    @ExceptionHandler(NotLoggedInException.class)
    public String handleNotLoggedIn() {
        return "redirect:/app";
    }

    /** 사용자 홈 - 내 주문 + 내 문의 */
    @GetMapping("/home")
    public String home(HttpSession session, Model model) {
        UserView user = resolveUser(session);
        List<InquiryListResponse> myInquiries = inquiryService.getInquiriesByCustomer(user.id());
        model.addAttribute("user", user);
        model.addAttribute("myInquiries", myInquiries);
        model.addAttribute("categoryLabels", CATEGORY_LABELS);
        return "user/home";
    }

    /** 문의 작성 폼 */
    @GetMapping("/inquiries/new")
    public String inquiryNew(HttpSession session, Model model) {
        UserView user = resolveUser(session);
        model.addAttribute("user", user);
        model.addAttribute("categoryLabels", CATEGORY_LABELS);
        model.addAttribute("orderRequiredCategories", ORDER_REQUIRED.stream().map(Enum::name).toList());
        return "user/inquiry-new";
    }

    /** 문의 상세 (유저용) */
    @GetMapping("/inquiries/{id}")
    public String inquiryDetail(@PathVariable Long id, HttpSession session, Model model) {
        UserView user = resolveUser(session);
        InquiryDetailResponse inquiry = inquiryService.getInquiry(id);
        List<InquiryMessageResponse> messages = inquiryService.getMessages(id);
        model.addAttribute("user", user);
        model.addAttribute("inquiry", inquiry);
        model.addAttribute("messages", messages);
        model.addAttribute("categoryLabels", CATEGORY_LABELS);
        return "user/inquiry-detail";
    }

    private UserView resolveUser(HttpSession session) {
        String userId = (String) session.getAttribute("userId");
        if (userId == null) {
            throw new NotLoggedInException();
        }
        return userViewAssembler.find(userId)
                .orElseThrow(NotLoggedInException::new);
    }

    static class NotLoggedInException extends RuntimeException {}
}
