package com.app.publishservice.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.app.publishservice.domain.enums.AppType;

import java.time.LocalDateTime;

@TableName("app_info")
public class AppInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String appName;

    private String packageName;

    private AppType appType;

    private String appDescription;

    private String copyrightNo;

    private String icpNo;

    private String appRecordNo;

    private String privacyUrl;

    private String userAgreementUrl;

    private Integer status = 1;

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
     * 获取应用名称。
     */
    public String getAppName() {
        return appName;
    }

    /**
     * 设置应用名称。
     */
    public void setAppName(String appName) {
        this.appName = appName;
    }

    /**
     * 获取包名称。
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * 设置包名称。
     */
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    /**
     * 获取应用类型。
     */
    public AppType getAppType() {
        return appType;
    }

    /**
     * 设置应用类型。
     */
    public void setAppType(AppType appType) {
        this.appType = appType;
    }

    /**
     * 获取应用 Description。
     */
    public String getAppDescription() {
        return appDescription;
    }

    /**
     * 设置应用 Description。
     */
    public void setAppDescription(String appDescription) {
        this.appDescription = appDescription;
    }

    /**
     * 获取Copyright No。
     */
    public String getCopyrightNo() {
        return copyrightNo;
    }

    /**
     * 设置Copyright No。
     */
    public void setCopyrightNo(String copyrightNo) {
        this.copyrightNo = copyrightNo;
    }

    /**
     * 获取Icp No。
     */
    public String getIcpNo() {
        return icpNo;
    }

    /**
     * 设置Icp No。
     */
    public void setIcpNo(String icpNo) {
        this.icpNo = icpNo;
    }

    /**
     * 获取应用记录 No。
     */
    public String getAppRecordNo() {
        return appRecordNo;
    }

    /**
     * 设置应用记录 No。
     */
    public void setAppRecordNo(String appRecordNo) {
        this.appRecordNo = appRecordNo;
    }

    /**
     * 获取Privacy URL。
     */
    public String getPrivacyUrl() {
        return privacyUrl;
    }

    /**
     * 设置Privacy URL。
     */
    public void setPrivacyUrl(String privacyUrl) {
        this.privacyUrl = privacyUrl;
    }

    /**
     * 获取User Agreement URL。
     */
    public String getUserAgreementUrl() {
        return userAgreementUrl;
    }

    /**
     * 设置User Agreement URL。
     */
    public void setUserAgreementUrl(String userAgreementUrl) {
        this.userAgreementUrl = userAgreementUrl;
    }

    /**
     * 获取状态。
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * 设置状态。
     */
    public void setStatus(Integer status) {
        this.status = status;
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
