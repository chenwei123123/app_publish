package com.app.publishservice.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum AppType {
    ANDROID(1),
    HarmonyOS(2);

    @EnumValue
    private final int code;

    /**
     * 初始化AppType。
     */
    AppType(int code) {
        this.code = code;
    }

    /**
     * 获取编码。
     */
    public int getCode() {
        return code;
    }

    /**
     * 处理编码相关逻辑。
     */
    public static AppType fromCode(int code) {
        for (AppType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported app type code: " + code);
    }
}
