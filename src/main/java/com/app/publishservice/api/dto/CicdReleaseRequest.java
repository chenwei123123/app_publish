package com.app.publishservice.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class CicdReleaseRequest {

    @NotNull
    private Long appId;

    @NotEmpty
    private List<String> storeTypes;

    private String updateLog;
    private String expectedVersionName;
    private Integer expectedVersionCode;
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

    public Integer getExpectedVersionCode() {
        return expectedVersionCode;
    }

    public void setExpectedVersionCode(Integer expectedVersionCode) {
        this.expectedVersionCode = expectedVersionCode;
    }

    public Boolean getExpectedReinforced() {
        return expectedReinforced;
    }

    public void setExpectedReinforced(Boolean expectedReinforced) {
        this.expectedReinforced = expectedReinforced;
    }
}
