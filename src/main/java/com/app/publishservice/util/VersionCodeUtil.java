package com.app.publishservice.util;

public final class VersionCodeUtil {

    /**
     * 初始化VersionCodeUtil。
     */
    private VersionCodeUtil() {
    }

    /**
     * 规范化相关数据。
     */
    public static String normalize(String versionCode) {
        if (versionCode == null) {
            return null;
        }
        String normalized = versionCode.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 校验Non Blank。
     */
    public static String requireNonBlank(String versionCode) {
        String normalized = normalize(versionCode);
        if (normalized == null) {
            throw new IllegalArgumentException("versionCode must not be blank");
        }
        return normalized;
    }
}
