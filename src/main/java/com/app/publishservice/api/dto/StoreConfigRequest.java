package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "应用商店账号配置请求")
public class StoreConfigRequest {

    @Schema(description = "配置 ID，更新场景可传")
    private Long id;

    @NotBlank
    @Schema(description = "应用商店类型，例如 vivo、oppo、huawei、xiaomi", requiredMode = Schema.RequiredMode.REQUIRED)
    private String storeType;

    @Schema(description = "账号名称")
    private String accountName;

    @Schema(description = "联系邮箱")
    private String email;

    @Schema(description = "联系手机号")
    private String phone;

    @Schema(description = "开放平台 Client ID")
    private String clientId;

    @Schema(description = "开放平台 Client Secret")
    private String clientSecret;

    @Schema(description = "平台公钥，当前主要用于小米渠道")
    private String publicKey;

    @Schema(description = "平台私钥，当前主要用于小米渠道")
    private String privateKey;

    @Schema(description = "固定 Token")
    private String token;

    @Schema(description = "IP 白名单")
    private String ipWhitelist;

    @Schema(description = "API 状态，1 表示启用，0 表示禁用")
    private Integer apiStatus = 1;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStoreType() {
        return storeType;
    }

    public void setStoreType(String storeType) {
        this.storeType = storeType;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getIpWhitelist() {
        return ipWhitelist;
    }

    public void setIpWhitelist(String ipWhitelist) {
        this.ipWhitelist = ipWhitelist;
    }

    public Integer getApiStatus() {
        return apiStatus;
    }

    public void setApiStatus(Integer apiStatus) {
        this.apiStatus = apiStatus;
    }
}
