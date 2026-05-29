package com.app.publishservice.repository;

import com.app.publishservice.domain.entity.AppApiTokenCache;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppApiTokenCacheRepository extends BaseMapper<AppApiTokenCache> {
}

