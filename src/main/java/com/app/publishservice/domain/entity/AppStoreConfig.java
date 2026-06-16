package com.app.publishservice.domain.entity;

import com.app.publishservice.domain.enums.StoreType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("app_store_config")
public class AppStoreConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private StoreType storeType;

    private String accountName;

    private String email;

    private String phone;

    private String clientId;

    private String clientSecret;

    @TableField("public_key")
    private String publicKey;

    @TableField("private_key")
    private String privateKey;

    private String token;

    private String ipWhitelist;

    private Integer apiStatus = 1;

    @TableField(fill = FieldFill.INSERT)
    private String createUser;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

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
    public StoreType getStoreType() {
        return storeType;
    }

    /**
     * 设置商店类型。
     */
    public void setStoreType(StoreType storeType) {
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

    /**
     * 获取Create User。
     */
    public String getCreateUser() {
        return createUser;
    }

    /**
     * 设置Create User。
     */
    public void setCreateUser(String createUser) {
        this.createUser = createUser;
    }

    /**
     * 获取Update User。
     */
    public String getUpdateUser() {
        return updateUser;
    }

    /**
     * 设置Update User。
     */
    public void setUpdateUser(String updateUser) {
        this.updateUser = updateUser;
    }

    /**
     * 获取Create 时间。
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置Create 时间。
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    /**
     * 获取Update 时间。
     */
    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    /**
     * 设置Update 时间。
     */
    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
