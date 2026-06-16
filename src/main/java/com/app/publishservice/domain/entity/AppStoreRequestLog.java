package com.app.publishservice.domain.entity;

import com.app.publishservice.domain.enums.StoreType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("app_store_request_log")
public class AppStoreRequestLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long releaseRecordId;

    private Long storeConfigId;

    private StoreType storeType;

    private String action;

    private Long requestOrder;

    private String requestMethod;

    private String requestUrl;

    private String requestParams;

    private String requestBody;

    private Integer responseStatusCode;

    private String responseBody;

    private String requestStatus;

    private String errorMessage;

    private Long durationMs;

    @TableField(fill = FieldFill.INSERT)
    private String createUser;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

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
     * 获取发布记录 Id。
     */
    public Long getReleaseRecordId() {
        return releaseRecordId;
    }

    /**
     * 设置发布记录 Id。
     */
    public void setReleaseRecordId(Long releaseRecordId) {
        this.releaseRecordId = releaseRecordId;
    }

    /**
     * 获取商店配置 Id。
     */
    public Long getStoreConfigId() {
        return storeConfigId;
    }

    /**
     * 设置商店配置 Id。
     */
    public void setStoreConfigId(Long storeConfigId) {
        this.storeConfigId = storeConfigId;
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
     * 获取Action。
     */
    public String getAction() {
        return action;
    }

    /**
     * 设置Action。
     */
    public void setAction(String action) {
        this.action = action;
    }

    /**
     * 获取请求 Order。
     */
    public Long getRequestOrder() {
        return requestOrder;
    }

    /**
     * 设置请求 Order。
     */
    public void setRequestOrder(Long requestOrder) {
        this.requestOrder = requestOrder;
    }

    /**
     * 获取请求 Method。
     */
    public String getRequestMethod() {
        return requestMethod;
    }

    /**
     * 设置请求 Method。
     */
    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }

    /**
     * 获取请求 URL。
     */
    public String getRequestUrl() {
        return requestUrl;
    }

    /**
     * 设置请求 URL。
     */
    public void setRequestUrl(String requestUrl) {
        this.requestUrl = requestUrl;
    }

    /**
     * 获取请求参数。
     */
    public String getRequestParams() {
        return requestParams;
    }

    /**
     * 设置请求参数。
     */
    public void setRequestParams(String requestParams) {
        this.requestParams = requestParams;
    }

    /**
     * 获取请求报文。
     */
    public String getRequestBody() {
        return requestBody;
    }

    /**
     * 设置请求报文。
     */
    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    /**
     * 获取响应状态编码。
     */
    public Integer getResponseStatusCode() {
        return responseStatusCode;
    }

    /**
     * 设置响应状态编码。
     */
    public void setResponseStatusCode(Integer responseStatusCode) {
        this.responseStatusCode = responseStatusCode;
    }

    /**
     * 获取响应报文。
     */
    public String getResponseBody() {
        return responseBody;
    }

    /**
     * 设置响应报文。
     */
    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    /**
     * 获取请求状态。
     */
    public String getRequestStatus() {
        return requestStatus;
    }

    /**
     * 设置请求状态。
     */
    public void setRequestStatus(String requestStatus) {
        this.requestStatus = requestStatus;
    }

    /**
     * 获取错误消息。
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 设置错误消息。
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * 获取Duration Ms。
     */
    public Long getDurationMs() {
        return durationMs;
    }

    /**
     * 设置Duration Ms。
     */
    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
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
     * 获取Create 时间。
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置Create 时间。
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    /**
     * 获取Update 时间。
     */
    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    /**
     * 设置Update 时间。
     */
    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
