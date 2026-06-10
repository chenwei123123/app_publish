package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "App create or update request")
public class AppUpsertRequest {

    @Schema(description = "App ID used for updates")
    private Long id;

    @NotBlank
    @Schema(description = "App name", requiredMode = Schema.RequiredMode.REQUIRED)
    private String appName;

    @NotBlank
    @Schema(description = "Package name", requiredMode = Schema.RequiredMode.REQUIRED)
    private String packageName;

    @NotNull
    @Min(1)
    @Max(2)
    @Schema(description = "App type: 1=Android 2=iOS", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer appType;

    @Schema(description = "App description")
    private String appDescription;

    @Schema(description = "Copyright number")
    private String copyrightNo;

    @Schema(description = "ICP filing number")
    private String icpNo;

    @Schema(description = "App record number")
    private String appRecordNo;

    @Schema(description = "Privacy policy URL")
    private String privacyUrl;

    @Schema(description = "User agreement URL")
    private String userAgreementUrl;

    @Schema(description = "Status: 1=enabled 0=disabled")
    private Integer status = 1;

    @Schema(description = "Version code")
    private String versionCode;

    @Schema(description = "Build code")
    private String buildCode;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Integer getAppType() {
        return appType;
    }

    public void setAppType(Integer appType) {
        this.appType = appType;
    }

    public String getAppDescription() {
        return appDescription;
    }

    public void setAppDescription(String appDescription) {
        this.appDescription = appDescription;
    }

    public String getCopyrightNo() {
        return copyrightNo;
    }

    public void setCopyrightNo(String copyrightNo) {
        this.copyrightNo = copyrightNo;
    }

    public String getIcpNo() {
        return icpNo;
    }

    public void setIcpNo(String icpNo) {
        this.icpNo = icpNo;
    }

    public String getAppRecordNo() {
        return appRecordNo;
    }

    public void setAppRecordNo(String appRecordNo) {
        this.appRecordNo = appRecordNo;
    }

    public String getPrivacyUrl() {
        return privacyUrl;
    }

    public void setPrivacyUrl(String privacyUrl) {
        this.privacyUrl = privacyUrl;
    }

    public String getUserAgreementUrl() {
        return userAgreementUrl;
    }

    public void setUserAgreementUrl(String userAgreementUrl) {
        this.userAgreementUrl = userAgreementUrl;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(String versionCode) {
        this.versionCode = versionCode;
    }

    public String getBuildCode() {
        return buildCode;
    }

    public void setBuildCode(String buildCode) {
        this.buildCode = buildCode;
    }
}
