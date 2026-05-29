package com.app.publishservice.service;

import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.service.model.StoreReviewResult;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.app.publishservice.service.model.TokenPayload;

public interface StorePublisher {

    TokenPayload refreshToken(AppStoreConfig storeConfig);

    StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token);

    StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token);
}
