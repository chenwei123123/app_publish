package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "应用商店账号配置响应")
public record StoreConfigResponse(
        @Schema(description = "配置 ID") Long id,
        @Schema(description = "应用商店类型") String storeType,
        @Schema(description = "账号名称") String accountName,
        @Schema(description = "联系邮箱") String email,
        @Schema(description = "联系电话") String phone,
        @Schema(description = "开放平台 Client ID") String clientId,
        @Schema(description = "开放平台 Client Secret") String clientSecret,
        @Schema(description = "平台公钥，当前主要用于小米渠道") String publicKey,
        @Schema(description = "平台私钥，当前主要用于小米渠道") String privateKey,
        @Schema(description = "固定 Token") String token,
        @Schema(description = "IP 白名单") String ipWhitelist,
        @Schema(description = "小米隐私政策地址") String privacyUrl,
        @Schema(description = "小米图标 Base64") String icon,
        @Schema(description = "应用宝 appId / 三星 contentId") String appId,
        @Schema(description = "API 状态，1 表示启用，0 表示禁用") Integer apiStatus,
        @Schema(description = "创建人") String createUser,
        @Schema(description = "更新人") String updateUser,
        @Schema(description = "创建时间") LocalDateTime createTime,
        @Schema(description = "更新时间") LocalDateTime updateTime
) {
}
