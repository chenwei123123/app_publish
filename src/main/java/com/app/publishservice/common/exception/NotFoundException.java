package com.app.publishservice.common.exception;

public class NotFoundException extends RuntimeException {

    /**
     * 初始化NotFoundException。
     */
    public NotFoundException(String message) {
        super(message);
    }
}
