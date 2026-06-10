package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Store config request")
public class StoreConfigRequest {

    @Schema(description = "Config ID used for updates")
    private Long id;

    @NotBlank
    @Schema(description = "Store type, such as vivo or xiaomi", requiredMode = Schema.RequiredMode.REQUIRED)
    private String storeType;

    @Schema(description = "Account name")
    private String accountName;

    @Schema(description = "Contact email")
    private String email;

    @Schema(description = "Contact phone")
    private String phone;

    @Schema(description = "Client ID")
    private String clientId;

    @Schema(description = "Client secret")
    private String clientSecret;

    @Schema(description = "Xiaomi public key")
    private String publicKey;

    @Schema(description = "Xiaomi private key")
    private String privateKey;

    @Schema(description = "Static token")
    private String token;

    @Schema(description = "IP whitelist")
    private String ipWhitelist;

    @Schema(description = "API status: 1 enabled, 0 disabled")
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
