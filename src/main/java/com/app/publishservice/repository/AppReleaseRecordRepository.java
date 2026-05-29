package com.app.publishservice.repository;

import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AppReleaseRecordRepository extends BaseMapper<AppReleaseRecord> {

    Page<AppReleaseRecord> selectReleaseRecordPage(
            Page<AppReleaseRecord> page,
            @Param("key") String key,
            @Param("appId") Long appId
    );

    AppReleaseRecord selectReleaseRecordDetail(@Param("releaseId") Long releaseId);

    List<AppReleaseRecord> selectReleaseRecordsByAppId(@Param("appId") Long appId);

    List<AppReleaseRecord> selectReviewingReleaseRecords(@Param("releaseStatus") String releaseStatus);
}
