package com.app.publishservice.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum AppType {
    ANDROID(1),
    IOS(2);

    @EnumValue
    private final int code;

    AppType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static AppType fromCode(int code) {
        for (AppType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported app type code: " + code);
    }
}
