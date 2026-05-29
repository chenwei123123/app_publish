package com.app.publishservice.service.model;

public record PackageMetadata(
        String packageType,
        String versionName,
        Integer versionCode,
        boolean reinforced,
        String checksum
) {
}

