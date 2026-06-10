package com.app.publishservice.common.exception;

import org.springframework.http.HttpStatus;

public class StoreApiException extends RuntimeException {

    private final HttpStatus status;

    public StoreApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public StoreApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
