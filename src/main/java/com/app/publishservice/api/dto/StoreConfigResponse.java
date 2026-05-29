package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "渠道配置响应")
public record StoreConfigResponse(
        @Schema(description = "渠道配置 ID") Long id,
        @Schema(description = "渠道类型") String storeType,
        @Schema(description = "开发者账号名称") String accountName,
        @Schema(description = "联系邮箱") String email,
        @Schema(description = "联系电话") String phone,
        @Schema(description = "开放平台 Client ID") String clientId,
        @Schema(description = "开放平台 Client Secret") String clientSecret,
        @Schema(description = "小米公钥") String miPublicKey,
        @Schema(description = "小米私钥") String miPrivateKey,
        @Schema(description = "固定 Token") String token,
        @Schema(description = "IP 白名单") String ipWhitelist,
        @Schema(description = "API 状态") Integer apiStatus,
        @Schema(description = "创建人") String createUser,
        @Schema(description = "更新人") String updateUser,
        @Schema(description = "创建时间") LocalDateTime createTime,
        @Schema(description = "更新时间") LocalDateTime updateTime
) {
}
