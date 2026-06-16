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

    /**
     * 获取Id。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置Id。
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取商店类型。
     */
    public String getStoreType() {
        return storeType;
    }

    /**
     * 设置商店类型。
     */
    public void setStoreType(String storeType) {
        this.storeType = storeType;
    }

    /**
     * 获取Account 名称。
     */
    public String getAccountName() {
        return accountName;
    }

    /**
     * 设置Account 名称。
     */
    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    /**
     * 获取Email。
     */
    public String getEmail() {
        return email;
    }

    /**
     * 设置Email。
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * 获取Phone。
     */
    public String getPhone() {
        return phone;
    }

    /**
     * 设置Phone。
     */
    public void setPhone(String phone) {
        this.phone = phone;
    }

    /**
     * 获取客户端 Id。
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * 设置客户端 Id。
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * 获取客户端 Secret。
     */
    public String getClientSecret() {
        return clientSecret;
    }

    /**
     * 设置客户端 Secret。
     */
    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    /**
     * 获取Public Key。
     */
    public String getPublicKey() {
        return publicKey;
    }

    /**
     * 设置Public Key。
     */
    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    /**
     * 获取Private Key。
     */
    public String getPrivateKey() {
        return privateKey;
    }

    /**
     * 设置Private Key。
     */
    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    /**
     * 获取令牌。
     */
    public String getToken() {
        return token;
    }

    /**
     * 设置令牌。
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * 获取Ip Whitelist。
     */
    public String getIpWhitelist() {
        return ipWhitelist;
    }

    /**
     * 设置Ip Whitelist。
     */
    public void setIpWhitelist(String ipWhitelist) {
        this.ipWhitelist = ipWhitelist;
    }

    /**
     * 获取API 状态。
     */
    public Integer getApiStatus() {
        return apiStatus;
    }

    /**
     * 设置API 状态。
     */
    public void setApiStatus(Integer apiStatus) {
        this.apiStatus = apiStatus;
    }
}
