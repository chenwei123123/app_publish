package com.app.publishservice.service.model;

public record PackageMetadata(
        String packageType,
        String versionName,
        String versionCode,
        boolean reinforced,
        String checksum
) {
}
