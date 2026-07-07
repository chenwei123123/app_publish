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

    @TableField("package_app_url")
    private String packageAppUrl;

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
     * 获取应用 Id。
     */
    public Long getAppId() {
        return appId;
    }

    /**
     * 设置应用 Id。
     */
    public void setAppId(Long appId) {
        this.appId = appId;
    }

    /**
     * 获取应用 Info。
     */
    public AppInfo getAppInfo() {
        return appInfo;
    }

    /**
     * 设置应用 Info。
     */
    public void setAppInfo(AppInfo appInfo) {
        this.appInfo = appInfo;
        this.appId = appInfo == null ? null : appInfo.getId();
    }

    /**
     * 获取版本名称。
     */
    public String getVersionName() {
        return versionName;
    }

    /**
     * 设置版本名称。
     */
    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    /**
     * 获取版本编码。
     */
    public String getVersionCode() {
        return versionCode;
    }

    /**
     * 设置版本编码。
     */
    public void setVersionCode(String versionCode) {
        this.versionCode = versionCode;
    }

    /**
     * 获取包 URL。
     */
    public String getPackageUrl() {
        if (hasText(packageUrl)) {
            return packageUrl;
        }
        if (hasText(packageAppUrl)) {
            return packageAppUrl;
        }
        if (hasText(packageUrl32)) {
            return packageUrl32;
        }
        return packageUrl64;
    }

    /**
     * 设置包 URL。
     */
    public void setPackageUrl(String packageUrl) {
        this.packageUrl = packageUrl;
    }

    /**
     * 获取包 Url32。
     */
    public String getPackageUrl32() {
        return packageUrl32;
    }

    /**
     * 设置包 Url32。
     */
    public void setPackageUrl32(String packageUrl32) {
        this.packageUrl32 = packageUrl32;
    }

    /**
     * 获取包 Url64。
     */
    public String getPackageUrl64() {
        return packageUrl64;
    }

    /**
     * 设置包 Url64。
     */
    public void setPackageUrl64(String packageUrl64) {
        this.packageUrl64 = packageUrl64;
    }

    public String getPackageAppUrl() {
        return packageAppUrl;
    }

    public void setPackageAppUrl(String packageAppUrl) {
        this.packageAppUrl = packageAppUrl;
    }

    /**
     * 获取Build 编码。
     */
    public String getBuildCode() {
        return buildCode;
    }

    /**
     * 设置Build 编码。
     */
    public void setBuildCode(String buildCode) {
        this.buildCode = buildCode;
    }

    /**
     * 获取Update 日志。
     */
    public String getUpdateLog() {
        return updateLog;
    }

    /**
     * 设置Update 日志。
     */
    public void setUpdateLog(String updateLog) {
        this.updateLog = updateLog;
    }

    /**
     * 获取Is Reinforce。
     */
    public Integer getIsReinforce() {
        return isReinforce;
    }

    /**
     * 设置Is Reinforce。
     */
    public void setIsReinforce(Integer reinforce) {
        isReinforce = reinforce;
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
     * 判断是否文本。
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
