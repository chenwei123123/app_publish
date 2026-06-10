package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "App version response")
public record AppVersionResponse(
        @Schema(description = "Version ID") Long id,
        @Schema(description = "App ID") Long appId,
        @Schema(description = "Version name") String versionName,
        @Schema(description = "Version code") String versionCode,
        @Schema(description = "Build code") String buildCode,
        @Schema(description = "Primary package URL") String packageUrl,
        @Schema(description = "32-bit package URL") String packageUrl32,
        @Schema(description = "64-bit package URL") String packageUrl64,
        @Schema(description = "Update log") String updateLog,
        @Schema(description = "Whether the package is reinforced") boolean reinforced,
        @Schema(description = "Created by") String createUser,
        @Schema(description = "Updated by") String updateUser,
        @Schema(description = "Creation time") LocalDateTime createTime
) {
}
