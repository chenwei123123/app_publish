package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "应用新增或更新请求")
public class AppUpsertRequest {

    @Schema(description = "用于更新请求的应用 ID")
    private Long id;

    @NotBlank
    @Schema(description = "应用名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String appName;

    @NotBlank
    @Schema(description = "包名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String packageName;

    @NotNull
    @Min(1)
    @Max(2)
    @Schema(description = "应用类型：1=安卓应用，2=iOS 应用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer appType;

    @Schema(description = "应用描述")
    private String appDescription;

    @Schema(description = "软件著作权登记号")
    private String copyrightNo;

    @Schema(description = "ICP 备案号")
    private String icpNo;

    @Schema(description = "应用备案号")
    private String appRecordNo;

    @Schema(description = "隐私政策地址")
    private String privacyUrl;

    @Schema(description = "用户协议地址")
    private String userAgreementUrl;

    @Schema(description = "状态：1=启用，0=禁用")
    private Integer status = 1;

    @Schema(description = "版本号")
    private Integer versionCode;



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

    public Integer getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(Integer versionCode) {
        this.versionCode = versionCode;
    }
}
