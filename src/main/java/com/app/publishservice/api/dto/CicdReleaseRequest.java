package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "CI/CD 发布请求")
public class CicdReleaseRequest {

    @NotNull
    @Schema(description = "应用 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long appId;

    @NotEmpty
    @Schema(description = "发布渠道列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> storeTypes;

    @Schema(description = "版本更新说明")
    private String updateLog;

    @Schema(description = "期望的版本名称，用于上传后校验")
    private String expectedVersionName;

    @Schema(description = "期望的版本号，用于上传后校验")
    private String expectedVersionCode;

    @Schema(description = "期望是否为加固包")
    private Boolean expectedReinforced;

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
     * 获取商店 Types。
     */
    public List<String> getStoreTypes() {
        return storeTypes;
    }

    /**
     * 设置商店 Types。
     */
    public void setStoreTypes(List<String> storeTypes) {
        this.storeTypes = storeTypes;
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
     * 获取Expected 版本名称。
     */
    public String getExpectedVersionName() {
        return expectedVersionName;
    }

    /**
     * 设置Expected 版本名称。
     */
    public void setExpectedVersionName(String expectedVersionName) {
        this.expectedVersionName = expectedVersionName;
    }

    /**
     * 获取Expected 版本编码。
     */
    public String getExpectedVersionCode() {
        return expectedVersionCode;
    }

    /**
     * 设置Expected 版本编码。
     */
    public void setExpectedVersionCode(String expectedVersionCode) {
        this.expectedVersionCode = expectedVersionCode;
    }

    /**
     * 获取Expected Reinforced。
     */
    public Boolean getExpectedReinforced() {
        return expectedReinforced;
    }

    /**
     * 设置Expected Reinforced。
     */
    public void setExpectedReinforced(Boolean expectedReinforced) {
        this.expectedReinforced = expectedReinforced;
    }
}
