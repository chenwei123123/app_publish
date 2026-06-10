package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "Store config response")
public record StoreConfigResponse(
        @Schema(description = "Store config ID") Long id,
        @Schema(description = "Store type") String storeType,
        @Schema(description = "Account name") String accountName,
        @Schema(description = "Contact email") String email,
        @Schema(description = "Contact phone") String phone,
        @Schema(description = "Client ID") String clientId,
        @Schema(description = "Client secret") String clientSecret,
        @Schema(description = "Xiaomi public key") String publicKey,
        @Schema(description = "Xiaomi private key") String privateKey,
        @Schema(description = "Static token") String token,
        @Schema(description = "IP whitelist") String ipWhitelist,
        @Schema(description = "API status: 1 enabled, 0 disabled") Integer apiStatus,
        @Schema(description = "Created by") String createUser,
        @Schema(description = "Updated by") String updateUser,
        @Schema(description = "Creation time") LocalDateTime createTime,
        @Schema(description = "Update time") LocalDateTime updateTime
) {
}
