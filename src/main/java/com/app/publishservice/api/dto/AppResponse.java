package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "应用响应")
public record AppResponse(
        @Schema(description = "应用 ID") Long id,
        @Schema(description = "应用名称") String appName,
        @Schema(description = "包名") String packageName,
        @Schema(description = "应用类型，1=安卓应用，2=iOS 应用") Integer appType,
        @Schema(description = "应用描述") String appDescription,
        @Schema(description = "软件著作权登记号") String copyrightNo,
        @Schema(description = "ICP 备案号") String icpNo,
        @Schema(description = "应用备案号") String appRecordNo,
        @Schema(description = "隐私政策地址") String privacyUrl,
        @Schema(description = "用户协议地址") String userAgreementUrl,
        @Schema(description = "状态：1=启用，0=禁用") Integer status,
        @Schema(description = "创建人") String createUser,
        @Schema(description = "更新人") String updateUser,
        @Schema(description = "创建时间") LocalDateTime createTime,
        @Schema(description = "更新时间") LocalDateTime updateTime,
        @Schema(description = "关联版本列表") List<AppVersionResponse> versions,
        @Schema(description = "关联发版记录列表") List<ReleaseRecordResponse> releaseRecords
) {
}
