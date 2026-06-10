package com.app.publishservice.util;

public final class VersionCodeUtil {

    private VersionCodeUtil() {
    }

    public static String normalize(String versionCode) {
        if (versionCode == null) {
            return null;
        }
        String normalized = versionCode.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static String requireNonBlank(String versionCode) {
        String normalized = normalize(versionCode);
        if (normalized == null) {
            throw new IllegalArgumentException("versionCode must not be blank");
        }
        return normalized;
    }
}
