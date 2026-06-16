package com.app.publishservice.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum ReleaseStatus {
    DRAFT("draft"),
    DOWNLOADING("downloading"),
    DOWNLOAD_SUCCESS("download_success"),
    DOWNLOAD_FAIL("download_fail"),
    API_PENDING("api_pending"),
    AUDITING("auditing"),
    PASS("pass"),
    REJECT("reject"),
    OFFLINE("offline");

    @EnumValue
    private final String code;

    /**
     * 初始化ReleaseStatus。
     */
    ReleaseStatus(String code) {
        this.code = code;
    }

    /**
     * 获取编码。
     */
    public String getCode() {
        return code;
    }

    /**
     * 判断是否Finished。
     */
    public boolean isFinished() {
        return this == PASS || this == REJECT || this == OFFLINE;
    }

    /**
     * 处理编码相关逻辑。
     */
    public static ReleaseStatus fromCode(String code) {
        for (ReleaseStatus value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported release status: " + code);
    }
}
