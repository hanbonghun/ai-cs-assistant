package com.aicsassistant.common.exception;

import com.aicsassistant.common.response.ApiErrorResponse;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handle(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiErrorResponse(ex.getCode(), ex.getMessage(), LocalDateTime.now()));
    }

    /** {@code @Valid} 검증 실패 — 필드 메시지를 한 줄로 합쳐 일관된 형태로 응답한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        if (message.isBlank()) {
            message = "요청 값이 유효하지 않습니다.";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_FAILED", message, LocalDateTime.now()));
    }

    /** JSON 본문 파싱 실패 (잘못된 형식). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("MALFORMED_REQUEST", "요청 본문을 읽을 수 없습니다.", LocalDateTime.now()));
    }

    /**
     * 존재하지 않는 정적 리소스 요청 — 404 로 조용히 끝낸다.
     *
     * <p>브라우저는 페이지마다 {@code favicon.ico} 를 자동 요청한다. 이것이 아래 범용 핸들러까지
     * 올라가면 요청마다 ERROR 와 스택트레이스가 찍혀 로그가 폭주한다 — 실제로 Railway 의
     * 초당 500줄 한도를 쳐서 정작 필요한 로그가 드롭됐다. 없는 리소스는 사고가 아니다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex) {
        log.debug("No static resource: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", LocalDateTime.now()));
    }

    /** 예상치 못한 예외 — 내부 에러 메시지를 그대로 노출하지 않고 일반화한 응답으로 변환. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception bubbled up to GlobalExceptionHandler", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("INTERNAL_ERROR", "처리 중 오류가 발생했습니다.", LocalDateTime.now()));
    }
}
