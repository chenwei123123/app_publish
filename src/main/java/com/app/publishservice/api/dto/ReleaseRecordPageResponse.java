package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "发版记录分页项响应")
public record ReleaseRecordPageResponse(
        @Schema(description = "发版记录 ID") Long id,
        @Schema(description = "应用 ID") Long appId,
        @Schema(description = "应用名称") String appName,
        @Schema(description = "应用包名") String packageName,
        @Schema(description = "应用描述") String appDescription,
        @Schema(description = "版本 ID") Long versionId,
        @Schema(description = "版本号") String versionCode,
        @Schema(description = "发布渠道类型") String storeType,
        @Schema(description = "发布模式") String releaseMode,
        @Schema(description = "发布状态") String releaseStatus,
        @Schema(description = "提交发布时间") LocalDateTime releaseTime,
        @Schema(description = "发布完成时间") LocalDateTime finishTime,
        @Schema(description = "创建人") String createUser,
        @Schema(description = "更新人") String updateUser
) {
}
