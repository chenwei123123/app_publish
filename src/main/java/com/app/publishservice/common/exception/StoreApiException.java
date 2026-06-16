package com.app.publishservice.common.exception;

import org.springframework.http.HttpStatus;

public class StoreApiException extends RuntimeException {

    private final HttpStatus status;

    /**
     * 初始化StoreApiException。
     */
    public StoreApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 初始化StoreApiException。
     */
    public StoreApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * 获取状态。
     */
    public HttpStatus getStatus() {
        return status;
    }
}
