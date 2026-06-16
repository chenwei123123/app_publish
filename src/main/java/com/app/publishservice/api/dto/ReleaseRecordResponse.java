package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "发版记录详情响应")
public record ReleaseRecordResponse(
        @Schema(description = "发版记录 ID") Long id,
        @Schema(description = "应用 ID") Long appId,
        @Schema(description = "应用名称") String appName,
        @Schema(description = "应用包名") String packageName,
        @Schema(description = "应用描述") String appDescription,
        @Schema(description = "版本 ID") Long versionId,
        @Schema(description = "版本号") String versionCode,
        @Schema(description = "发布渠道类型") String storeType,
        @Schema(description = "发布模式") String releaseMode,
        @Schema(description = "发布类型，1 表示全量发布，2 表示分阶段发布") Long releaseType,
        @Schema(description = "灰度比例") Long grayPercent,
        @Schema(description = "灰度开始时间") LocalDateTime grayStartTime,
        @Schema(description = "灰度结束时间") LocalDateTime grayEndTime,
        @Schema(description = "发布状态") String releaseStatus,
        @Schema(description = "商店侧发布单号") String storeReleaseId,
        @Schema(description = "驳回原因") String rejectReason,
        @Schema(description = "第三方 API 请求日志") String apiRequestLog,
        @Schema(description = "第三方 API 响应日志") String apiResponseLog,
        @Schema(description = "提交发布时间") LocalDateTime releaseTime,
        @Schema(description = "发布完成时间") LocalDateTime finishTime,
        @Schema(description = "创建人") String createUser,
        @Schema(description = "更新人") String updateUser
) {
}
