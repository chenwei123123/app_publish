package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "发版任务日志响应")
public record ReleaseTaskLogResponse(
        @Schema(description = "日志 ID") Long id,
        @Schema(description = "发版记录 ID") Long releaseRecordId,
        @Schema(description = "执行动作") String action,
        @Schema(description = "变更前状态") String statusBefore,
        @Schema(description = "变更后状态") String statusAfter,
        @Schema(description = "日志消息") String message,
        @Schema(description = "附加载荷") String payload,
        @Schema(description = "创建人") String createUser,
        @Schema(description = "更新人") String updateUser,
        @Schema(description = "创建时间") LocalDateTime createTime
) {
}
