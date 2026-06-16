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

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    public List<String> getStoreTypes() {
        return storeTypes;
    }

    public void setStoreTypes(List<String> storeTypes) {
        this.storeTypes = storeTypes;
    }

    public String getUpdateLog() {
        return updateLog;
    }

    public void setUpdateLog(String updateLog) {
        this.updateLog = updateLog;
    }

    public String getExpectedVersionName() {
        return expectedVersionName;
    }

    public void setExpectedVersionName(String expectedVersionName) {
        this.expectedVersionName = expectedVersionName;
    }

    public String getExpectedVersionCode() {
        return expectedVersionCode;
    }

    public void setExpectedVersionCode(String expectedVersionCode) {
        this.expectedVersionCode = expectedVersionCode;
    }

    public Boolean getExpectedReinforced() {
        return expectedReinforced;
    }

    public void setExpectedReinforced(Boolean expectedReinforced) {
        this.expectedReinforced = expectedReinforced;
    }
}
