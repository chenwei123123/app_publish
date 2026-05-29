package com.app.publishservice.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum TokenType {
    ACCESS_TOKEN("access_token"),
    JWT("jwt"),
    STATIC("static");

    @EnumValue
    private final String code;

    TokenType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
