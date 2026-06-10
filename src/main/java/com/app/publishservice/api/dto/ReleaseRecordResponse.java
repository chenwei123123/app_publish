package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "Release record response")
public record ReleaseRecordResponse(
        @Schema(description = "Release record ID") Long id,
        @Schema(description = "App ID") Long appId,
        @Schema(description = "App name") String appName,
        @Schema(description = "Package name") String packageName,
        @Schema(description = "App description") String appDescription,
        @Schema(description = "Version ID") Long versionId,
        @Schema(description = "Version code") String versionCode,
        @Schema(description = "Store type") String storeType,
        @Schema(description = "Release mode") String releaseMode,
        @Schema(description = "Release type: 1=full 2=staged") Long releaseType,
        @Schema(description = "Gray percentage") Long grayPercent,
        @Schema(description = "Gray start time") LocalDateTime grayStartTime,
        @Schema(description = "Gray end time") LocalDateTime grayEndTime,
        @Schema(description = "Release status") String releaseStatus,
        @Schema(description = "Store release ID") String storeReleaseId,
        @Schema(description = "Reject reason") String rejectReason,
        @Schema(description = "API request log") String apiRequestLog,
        @Schema(description = "API response log") String apiResponseLog,
        @Schema(description = "Release time") LocalDateTime releaseTime,
        @Schema(description = "Finish time") LocalDateTime finishTime,
        @Schema(description = "Created by") String createUser,
        @Schema(description = "Updated by") String updateUser
) {
}
