package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "Package upload response")
public record PackageUploadResponse(
        @Schema(description = "Version ID") Long versionId,
        @Schema(description = "App ID") Long appId,
        @Schema(description = "Package type") String packageType,
        @Schema(description = "Stored file path") String storedPath,
        @Schema(description = "Version name") String versionName,
        @Schema(description = "Version code") String versionCode,
        @Schema(description = "Whether the package is reinforced") boolean reinforced,
        @Schema(description = "File checksum") String checksum,
        @Schema(description = "Upload time") LocalDateTime createTime
) {
}
