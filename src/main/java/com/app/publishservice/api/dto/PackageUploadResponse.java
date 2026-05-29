package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "安装包上传响应")
public record PackageUploadResponse(
        @Schema(description = "版本 ID") Long versionId,
        @Schema(description = "应用 ID") Long appId,
        @Schema(description = "安装包类型") String packageType,
        @Schema(description = "文件存储路径") String storedPath,
        @Schema(description = "版本名称") String versionName,
        @Schema(description = "版本号") Integer versionCode,
        @Schema(description = "是否为加固包") boolean reinforced,
        @Schema(description = "文件校验值") String checksum,
        @Schema(description = "上传时间") LocalDateTime createTime
) {
}
