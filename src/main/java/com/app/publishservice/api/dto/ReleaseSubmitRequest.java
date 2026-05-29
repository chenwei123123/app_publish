package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "发布提交请求")
public class ReleaseSubmitRequest {

    @NotNull
    @Schema(description = "版本 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long versionId;

    @NotEmpty
    @Schema(description = "发布渠道列表，例如 vivo、xiaomi", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> storeTypes;

    @Schema(description = "发布模式，默认 api")
    private String releaseMode = "api";

    @Schema(description = "发布类型：1全量 2分阶段发布")
    private Long releaseType = 1L;

    @Schema(description = "灰度比例，仅分阶段发布时生效")
    private Long grayPercent;

    @Schema(description = "灰度开始时间，仅分阶段发布时生效")
    private LocalDateTime grayStartTime;

    @Schema(description = "灰度结束时间，仅分阶段发布时生效")
    private LocalDateTime grayEndTime;

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public List<String> getStoreTypes() {
        return storeTypes;
    }

    public void setStoreTypes(List<String> storeTypes) {
        this.storeTypes = storeTypes;
    }

    public String getReleaseMode() {
        return releaseMode;
    }

    public void setReleaseMode(String releaseMode) {
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
}
