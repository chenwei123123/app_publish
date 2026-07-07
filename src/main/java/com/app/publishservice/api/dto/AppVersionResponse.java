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
        @Schema(description = "Primary package location") String packageUrl,
        @Schema(description = "32-bit package location") String packageUrl32,
        @Schema(description = "64-bit package location") String packageUrl64,
        @Schema(description = "Harmony package location") String packageAppUrl,
        @Schema(description = "Update log") String updateLog,
        @Schema(description = "Whether reinforced") boolean reinforced,
        @Schema(description = "Create user") String createUser,
        @Schema(description = "Update user") String updateUser,
        @Schema(description = "Create time") LocalDateTime createTime
) {
}
