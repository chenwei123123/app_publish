package com.app.publishservice.service;

import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.service.model.StoreReviewResult;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.app.publishservice.service.model.TokenPayload;

public interface StorePublisher {

    /**
     * 刷新令牌。
     */
    TokenPayload refreshToken(AppStoreConfig storeConfig);

    /**
     * 提交发布。
     */
    StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token);

    /**
     * 查询审核。
     */
    StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token);
}
