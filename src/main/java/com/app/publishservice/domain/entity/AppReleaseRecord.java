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

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public AppInfo getAppInfo() {
        return appInfo;
    }

    public void setAppInfo(AppInfo appInfo) {
        this.appInfo = appInfo;
        this.appId = appInfo == null ? null : appInfo.getId();
    }

    public AppVersion getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(AppVersion appVersion) {
        this.appVersion = appVersion;
        this.versionId = appVersion == null ? null : appVersion.getId();
    }

    public StoreType getStoreType() {
        return storeType;
    }

    public void setStoreType(StoreType storeType) {
        this.storeType = storeType;
    }

    public ReleaseMode getReleaseMode() {
        return releaseMode;
    }

    public void setReleaseMode(ReleaseMode releaseMode) {
        this.releaseMode = releaseMode;
    }

    public Long getReleaseType() {
        return releaseType;
    }

    public void setReleaseType(Long releaseType) {
        this.releaseType = releaseType;
    }

    public Long getGrayPercent() {
        return grayPercent;
    }

    public void setGrayPercent(Long grayPercent) {
        this.grayPercent = grayPercent;
    }

    public LocalDateTime getGrayStartTime() {
        return grayStartTime;
    }

    public void setGrayStartTime(LocalDateTime grayStartTime) {
        this.grayStartTime = grayStartTime;
    }

    public LocalDateTime getGrayEndTime() {
        return grayEndTime;
    }

    public void setGrayEndTime(LocalDateTime grayEndTime) {
        this.grayEndTime = grayEndTime;
    }

    public ReleaseStatus getReleaseStatus() {
        return releaseStatus;
    }

    public void setReleaseStatus(ReleaseStatus releaseStatus) {
        this.releaseStatus = releaseStatus;
    }

    public String getStoreReleaseId() {
        return storeReleaseId;
    }

    public void setStoreReleaseId(String storeReleaseId) {
        this.storeReleaseId = storeReleaseId;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getApiRequestLog() {
        return apiRequestLog;
    }

    public void setApiRequestLog(String apiRequestLog) {
        this.apiRequestLog = apiRequestLog;
    }

    public String getApiResponseLog() {
        return apiResponseLog;
    }

    public void setApiResponseLog(String apiResponseLog) {
        this.apiResponseLog = apiResponseLog;
    }

    public LocalDateTime getReleaseTime() {
        return releaseTime;
    }

    public void setReleaseTime(LocalDateTime releaseTime) {
        this.releaseTime = releaseTime;
    }

    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(LocalDateTime finishTime) {
        this.finishTime = finishTime;
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

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getAppDescription() {
        return appDescription;
    }

    public void setAppDescription(String appDescription) {
        this.appDescription = appDescription;
    }

    public String getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(String versionCode) {
        this.versionCode = versionCode;
    }
}
