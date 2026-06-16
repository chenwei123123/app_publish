package com.app.publishservice.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("app_api_token_cache")
public class AppApiTokenCache {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long storeConfigId;

    private String tokenType;

    private String tokenValue;

    private LocalDateTime expireTime;

    @TableField(fill = FieldFill.INSERT)
    private String createUser;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private AppStoreConfig storeConfig;

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
     * 获取商店配置 Id。
     */
    public Long getStoreConfigId() {
        return storeConfigId;
    }

    /**
     * 设置商店配置 Id。
     */
    public void setStoreConfigId(Long storeConfigId) {
        this.storeConfigId = storeConfigId;
    }

    /**
     * 获取商店配置。
     */
    public AppStoreConfig getStoreConfig() {
        return storeConfig;
    }

    /**
     * 设置商店配置。
     */
    public void setStoreConfig(AppStoreConfig storeConfig) {
        this.storeConfig = storeConfig;
        this.storeConfigId = storeConfig == null ? null : storeConfig.getId();
    }

    /**
     * 获取令牌类型。
     */
    public String getTokenType() {
        return tokenType;
    }

    /**
     * 设置令牌类型。
     */
    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    /**
     * 获取令牌值。
     */
    public String getTokenValue() {
        return tokenValue;
    }

    /**
     * 设置令牌值。
     */
    public void setTokenValue(String tokenValue) {
        this.tokenValue = tokenValue;
    }

    /**
     * 获取Expire 时间。
     */
    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    /**
     * 设置Expire 时间。
     */
    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
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
