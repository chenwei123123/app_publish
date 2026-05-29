package com.app.publishservice.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("app_release_task_log")
public class AppReleaseTaskLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long releaseRecordId;

    private String action;

    private String statusBefore;

    private String statusAfter;

    private String message;

    private String payload;

    @TableField(fill = FieldFill.INSERT)
    private String createUser;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(exist = false)
    private AppReleaseRecord releaseRecord;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReleaseRecordId() {
        return releaseRecordId;
    }

    public void setReleaseRecordId(Long releaseRecordId) {
        this.releaseRecordId = releaseRecordId;
    }

    public AppReleaseRecord getReleaseRecord() {
        return releaseRecord;
    }

    public void setReleaseRecord(AppReleaseRecord releaseRecord) {
        this.releaseRecord = releaseRecord;
        this.releaseRecordId = releaseRecord == null ? null : releaseRecord.getId();
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getStatusBefore() {
        return statusBefore;
    }

    public void setStatusBefore(String statusBefore) {
        this.statusBefore = statusBefore;
    }

    public String getStatusAfter() {
        return statusAfter;
    }

    public void setStatusAfter(String statusAfter) {
        this.statusAfter = statusAfter;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
