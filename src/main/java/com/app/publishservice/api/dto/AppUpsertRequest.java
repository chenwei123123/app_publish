package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "应用新增或更新请求")
public class AppUpsertRequest {

    @Schema(description = "应用 ID，更新场景可传")
    private Long id;

    @NotBlank
    @Schema(description = "应用名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String appName;

    @NotBlank
    @Schema(description = "应用包名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String packageName;

    @NotNull
    @Min(1)
    @Max(2)
    @Schema(description = "应用类型，1 表示 Android，2 表示 iOS", requiredMode = Schema.RequiredMode.REQUIRED)
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

    @Schema(description = "状态，1 表示启用，0 表示禁用")
    private Integer status = 1;

    @Schema(description = "版本号")
    private String versionCode;

    @Schema(description = "构建号")
    private String buildCode;

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
    public Integer getAppType() {
        return appType;
    }

    /**
     * 设置应用类型。
     */
    public void setAppType(Integer appType) {
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
}
