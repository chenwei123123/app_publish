package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "发版提交请求")
public class ReleaseSubmitRequest {

    @NotNull
    @Schema(description = "版本 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long versionId;

    @NotEmpty
    @Schema(description = "发布渠道列表，例如 vivo、xiaomi", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> storeTypes;

    @Schema(description = "发布模式，默认 api")
    private String releaseMode = "api";

    @Schema(description = "发布类型，1 表示全量发布，2 表示分阶段发布")
    private Long releaseType = 1L;

    @Schema(description = "灰度比例，仅分阶段发布时生效")
    private Long grayPercent;

    @Schema(description = "灰度开始时间，仅分阶段发布时生效")
    private LocalDateTime grayStartTime;

    @Schema(description = "灰度结束时间，仅分阶段发布时生效")
    private LocalDateTime grayEndTime;

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
     * 获取发布 Mode。
     */
    public String getReleaseMode() {
        return releaseMode;
    }

    /**
     * 设置发布 Mode。
     */
    public void setReleaseMode(String releaseMode) {
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
}
