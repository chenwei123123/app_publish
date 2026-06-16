package com.app.publishservice.repository;

import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AppReleaseRecordRepository extends BaseMapper<AppReleaseRecord> {

    /**
     * 处理select 发布记录分页相关逻辑。
     */
    Page<AppReleaseRecord> selectReleaseRecordPage(
            Page<AppReleaseRecord> page,
            @Param("key") String key,
            @Param("appId") Long appId
    );

    /**
     * 处理select 发布记录详情相关逻辑。
     */
    AppReleaseRecord selectReleaseRecordDetail(@Param("releaseId") Long releaseId);

    /**
     * 处理select 发布记录应用 Id相关逻辑。
     */
    List<AppReleaseRecord> selectReleaseRecordsByAppId(@Param("appId") Long appId);

    /**
     * 处理select Reviewing 发布记录相关逻辑。
     */
    List<AppReleaseRecord> selectReviewingReleaseRecords(@Param("releaseStatus") String releaseStatus);
}
