package com.app.publishservice.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum TokenType {
    ACCESS_TOKEN("access_token"),
    JWT("jwt"),
    STATIC("static");

    @EnumValue
    private final String code;

    /**
     * 初始化TokenType。
     */
    TokenType(String code) {
        this.code = code;
    }

    /**
     * 获取编码。
     */
    public String getCode() {
        return code;
    }
}
