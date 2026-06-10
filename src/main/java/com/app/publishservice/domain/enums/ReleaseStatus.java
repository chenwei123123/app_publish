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

    ReleaseStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public boolean isFinished() {
        return this == PASS || this == REJECT || this == OFFLINE;
    }

    public static ReleaseStatus fromCode(String code) {
        for (ReleaseStatus value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported release status: " + code);
    }
}
