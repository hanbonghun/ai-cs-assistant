package com.aicsassistant.common.exception;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Thymeleaf 뷰 컨트롤러 전용 예외 핸들러.
 *
 * <p>{@code GlobalExceptionHandler} 를 REST 전용으로 좁힌 뒤, 뷰에서 던진 {@link ApiException}
 * 은 아무도 번역하지 않아 Spring Boot 기본 처리로 넘어갔다. 화면은 {@code error.html} 이 떴지만
 * 상태 코드가 전부 500 이 됐다 — 없는 문의를 열면 404 여야 하는데 500 이라고 말하는 셈이다.
 *
 * <p>범위를 <b>패키지</b>로 가른 이유: {@code @RestController} 는 {@code @Controller} 를
 * 메타 애너테이션으로 갖는다. {@code annotations = Controller.class} 로 걸면 REST 컨트롤러까지
 * 걸려 두 advice 가 겹친다. 뷰 컨트롤러는 모두 {@code com.aicsassistant.ui.controller} 에 있다.
 */
@Slf4j
@ControllerAdvice(basePackages = "com.aicsassistant.ui.controller")
public class ViewExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public String handle(ApiException ex, Model model, HttpServletResponse response) {
        // 4xx 는 사용자 실수(없는 문의 열기 등)라 스택트레이스를 남기지 않는다.
        if (ex.getStatus().is5xxServerError()) {
            log.error("View request failed: {}", ex.getCode(), ex);
        } else {
            log.debug("View request rejected: {} {}", ex.getStatus().value(), ex.getCode());
        }
        response.setStatus(ex.getStatus().value());
        model.addAttribute("status", ex.getStatus().value());
        return "error";
    }
}
