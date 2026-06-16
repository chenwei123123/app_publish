package com.app.publishservice.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum ReleaseMode {
    MANUAL("manual"),
    API("api");

    @EnumValue
    private final String code;

    /**
     * 初始化ReleaseMode。
     */
    ReleaseMode(String code) {
        this.code = code;
    }

    /**
     * 获取编码。
     */
    public String getCode() {
        return code;
    }

    /**
     * 处理编码相关逻辑。
     */
    public static ReleaseMode fromCode(String code) {
        for (ReleaseMode value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported release mode: " + code);
    }
}
