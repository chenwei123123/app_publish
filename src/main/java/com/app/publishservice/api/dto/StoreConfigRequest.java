package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "全局渠道配置请求")
public class StoreConfigRequest {

    @Schema(description = "用于更新请求的渠道配置 ID")
    private Long id;

    @NotBlank
    @Schema(description = "渠道类型，例如 vivo 或 xiaomi", requiredMode = Schema.RequiredMode.REQUIRED)
    private String storeType;

    @Schema(description = "开发者账号名称")
    private String accountName;

    @Schema(description = "联系邮箱")
    private String email;

    @Schema(description = "联系电话")
    private String phone;

    @Schema(description = "开放平台 Client ID")
    private String clientId;

    @Schema(description = "开放平台 Client Secret")
    private String clientSecret;

    @Schema(description = "小米公钥")
    private String miPublicKey;

    @Schema(description = "小米私钥")
    private String miPrivateKey;

    @Schema(description = "固定 Token")
    private String token;

    @Schema(description = "IP 白名单")
    private String ipWhitelist;

    @Schema(description = "API 状态：1=启用，0=禁用")
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

    public String getMiPublicKey() {
        return miPublicKey;
    }

    public void setMiPublicKey(String miPublicKey) {
        this.miPublicKey = miPublicKey;
    }

    public String getMiPrivateKey() {
        return miPrivateKey;
    }

    public void setMiPrivateKey(String miPrivateKey) {
        this.miPrivateKey = miPrivateKey;
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
