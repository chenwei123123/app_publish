package com.app.publishservice.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum ReleaseMode {
    MANUAL("manual"),
    API("api");

    @EnumValue
    private final String code;

    ReleaseMode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ReleaseMode fromCode(String code) {
        for (ReleaseMode value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported release mode: " + code);
    }
}
