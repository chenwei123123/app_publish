package com.app.publishservice.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.app.publishservice.domain.enums.ReleaseMode;
import com.app.publishservice.domain.enums.ReleaseStatus;
import com.app.publishservice.domain.enums.StoreType;

import java.time.LocalDateTime;

@TableName("app_release_record")
public class AppReleaseRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appId;

    private Long versionId;

    private StoreType storeType;

    private ReleaseMode releaseMode = ReleaseMode.API;

    private Long releaseType = 1L;

    private Long grayPercent;

    private LocalDateTime grayStartTime;

    private LocalDateTime grayEndTime;

    private ReleaseStatus releaseStatus = ReleaseStatus.DRAFT;

    private String storeReleaseId;

    private String rejectReason;

    private String apiRequestLog;

    private String apiResponseLog;

    private LocalDateTime releaseTime;

    private LocalDateTime finishTime;

    @TableField(fill = FieldFill.INSERT)
    private String createUser;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;

    @TableField(exist = false)
    private AppInfo appInfo;

    @TableField(exist = false)
    private AppVersion appVersion;

    @TableField(exist = false)
    private String appName;

    @TableField(exist = false)
    private String packageName;

    @TableField(exist = false)
    private String appDescription;

    @TableField(exist = false)
    private String versionCode;

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
     * 获取版本 Id。
     */
    public Long getVersionId() {
        return versionId;
    }

    /**
     * 设置版本 Id。
     */
    public void setVersionId(Long versionId) {
        this.versionId = versionId;
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
     * 获取应用版本。
     */
    public AppVersion getAppVersion() {
        return appVersion;
    }

    /**
     * 设置应用版本。
     */
    public void setAppVersion(AppVersion appVersion) {
        this.appVersion = appVersion;
        this.versionId = appVersion == null ? null : appVersion.getId();
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
     * 获取发布 Mode。
     */
    public ReleaseMode getReleaseMode() {
        return releaseMode;
    }

    /**
     * 设置发布 Mode。
     */
    public void setReleaseMode(ReleaseMode releaseMode) {
        this.releaseMode = releaseMode;
    }

    /**
     * 获取发布类型。
     */
    public Long getReleaseType() {
        return releaseType;
    }

    /**
     * 设置发布类型。
     */
    public void setReleaseType(Long releaseType) {
        this.releaseType = releaseType;
    }

    /**
     * 获取灰度 Percent。
     */
    public Long getGrayPercent() {
        return grayPercent;
    }

    /**
     * 设置灰度 Percent。
     */
    public void setGrayPercent(Long grayPercent) {
        this.grayPercent = grayPercent;
    }

    /**
     * 获取灰度 Start 时间。
     */
    public LocalDateTime getGrayStartTime() {
        return grayStartTime;
    }

    /**
     * 设置灰度 Start 时间。
     */
    public void setGrayStartTime(LocalDateTime grayStartTime) {
        this.grayStartTime = grayStartTime;
    }

    /**
     * 获取灰度 End 时间。
     */
    public LocalDateTime getGrayEndTime() {
        return grayEndTime;
    }

    /**
     * 设置灰度 End 时间。
     */
    public void setGrayEndTime(LocalDateTime grayEndTime) {
        this.grayEndTime = grayEndTime;
    }

    /**
     * 获取发布状态。
     */
    public ReleaseStatus getReleaseStatus() {
        return releaseStatus;
    }

    /**
     * 设置发布状态。
     */
    public void setReleaseStatus(ReleaseStatus releaseStatus) {
        this.releaseStatus = releaseStatus;
    }

    /**
     * 获取商店发布 Id。
     */
    public String getStoreReleaseId() {
        return storeReleaseId;
    }

    /**
     * 设置商店发布 Id。
     */
    public void setStoreReleaseId(String storeReleaseId) {
        this.storeReleaseId = storeReleaseId;
    }

    /**
     * 获取Reject Reason。
     */
    public String getRejectReason() {
        return rejectReason;
    }

    /**
     * 设置Reject Reason。
     */
    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    /**
     * 获取API 请求日志。
     */
    public String getApiRequestLog() {
        return apiRequestLog;
    }

    /**
     * 设置API 请求日志。
     */
    public void setApiRequestLog(String apiRequestLog) {
        this.apiRequestLog = apiRequestLog;
    }

    /**
     * 获取API 响应日志。
     */
    public String getApiResponseLog() {
        return apiResponseLog;
    }

    /**
     * 设置API 响应日志。
     */
    public void setApiResponseLog(String apiResponseLog) {
        this.apiResponseLog = apiResponseLog;
    }

    /**
     * 获取发布时间。
     */
    public LocalDateTime getReleaseTime() {
        return releaseTime;
    }

    /**
     * 设置发布时间。
     */
    public void setReleaseTime(LocalDateTime releaseTime) {
        this.releaseTime = releaseTime;
    }

    /**
     * 获取Finish 时间。
     */
    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    /**
     * 设置Finish 时间。
     */
    public void setFinishTime(LocalDateTime finishTime) {
        this.finishTime = finishTime;
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
}
