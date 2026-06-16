package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "应用版本响应")
public record AppVersionResponse(
        @Schema(description = "版本 ID") Long id,
        @Schema(description = "应用 ID") Long appId,
        @Schema(description = "版本名称") String versionName,
        @Schema(description = "版本号") String versionCode,
        @Schema(description = "构建号") String buildCode,
        @Schema(description = "主安装包访问地址") String packageUrl,
        @Schema(description = "32 位安装包访问地址") String packageUrl32,
        @Schema(description = "64 位安装包访问地址") String packageUrl64,
        @Schema(description = "版本更新说明") String updateLog,
        @Schema(description = "是否为加固包") boolean reinforced,
        @Schema(description = "创建人") String createUser,
        @Schema(description = "更新人") String updateUser,
        @Schema(description = "创建时间") LocalDateTime createTime
) {
}
