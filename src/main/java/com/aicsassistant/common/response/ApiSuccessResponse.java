package com.aicsassistant.common.response;

import java.time.LocalDateTime;

public class ApiSuccessResponse<T> {

    private final String message;
    private final T data;
    private final LocalDateTime timestamp;

    public ApiSuccessResponse(String message, T data, LocalDateTime timestamp) {
        this.message = message;
        this.data = data;
        this.timestamp = timestamp;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
