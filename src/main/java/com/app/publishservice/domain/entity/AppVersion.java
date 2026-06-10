package com.app.publishservice.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("app_version")
public class AppVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appId;

    private String versionName;

    private String versionCode;

    @TableField(exist = false)
    private String packageUrl;

    @TableField("package_url_32")
    private String packageUrl32;

    @TableField("package_url_64")
    private String packageUrl64;

    private String buildCode;

    private String updateLog;

    private Integer isReinforce = 0;

    @TableField(fill = FieldFill.INSERT)
    private String createUser;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(exist = false)
    private AppInfo appInfo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    public AppInfo getAppInfo() {
        return appInfo;
    }

    public void setAppInfo(AppInfo appInfo) {
        this.appInfo = appInfo;
        this.appId = appInfo == null ? null : appInfo.getId();
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public String getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(String versionCode) {
        this.versionCode = versionCode;
    }

    public String getPackageUrl() {
        if (hasText(packageUrl)) {
            return packageUrl;
        }
        if (hasText(packageUrl32)) {
            return packageUrl32;
        }
        return packageUrl64;
    }

    public void setPackageUrl(String packageUrl) {
        this.packageUrl = packageUrl;
    }

    public String getPackageUrl32() {
        return packageUrl32;
    }

    public void setPackageUrl32(String packageUrl32) {
        this.packageUrl32 = packageUrl32;
    }

    public String getPackageUrl64() {
        return packageUrl64;
    }

    public void setPackageUrl64(String packageUrl64) {
        this.packageUrl64 = packageUrl64;
    }

    public String getBuildCode() {
        return buildCode;
    }

    public void setBuildCode(String buildCode) {
        this.buildCode = buildCode;
    }

    public String getUpdateLog() {
        return updateLog;
    }

    public void setUpdateLog(String updateLog) {
        this.updateLog = updateLog;
    }

    public Integer getIsReinforce() {
        return isReinforce;
    }

    public void setIsReinforce(Integer reinforce) {
        isReinforce = reinforce;
    }

    public String getCreateUser() {
        return createUser;
    }

    public void setCreateUser(String createUser) {
        this.createUser = createUser;
    }

    public String getUpdateUser() {
        return updateUser;
    }

    public void setUpdateUser(String updateUser) {
        this.updateUser = updateUser;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
