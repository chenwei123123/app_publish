package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.service.model.StoreReviewResult;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.app.publishservice.service.model.TokenPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class ConfigurableStorePublisher extends AbstractStorePlatformPublisher {

    private final List<StorePlatformPublisher> storePlatformPublishers;

    /**
     * 初始化ConfigurableStorePublisher。
     */
    public ConfigurableStorePublisher(RestClient restClient, ObjectMapper objectMapper, AppProperties appProperties) {
        this(restClient, objectMapper, appProperties, null);
    }

    /**
     * 初始化ConfigurableStorePublisher。
     */
    @Autowired
    public ConfigurableStorePublisher(
            RestClient restClient,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            StoreRequestLogService storeRequestLogService
    ) {
        super(restClient, objectMapper, appProperties, storeRequestLogService);
        this.storePlatformPublishers = List.of(
                new VivoStorePlatformPublisher(restClient, objectMapper, appProperties, storeRequestLogService),
                new OppoStorePlatformPublisher(restClient, objectMapper, appProperties, storeRequestLogService),
                new HuaweiStorePlatformPublisher(restClient, objectMapper, appProperties, storeRequestLogService),
                new RongyaoStorePlatformPublisher(restClient, objectMapper, appProperties, storeRequestLogService),
                new XiaomiStorePlatformPublisher(restClient, objectMapper, appProperties, storeRequestLogService),
                new SanxingStorePlatformPublisher(restClient, objectMapper, appProperties, storeRequestLogService),
                new YingyongbaoStorePlatformPublisher(restClient, objectMapper, appProperties, storeRequestLogService)
        );
    }

    /**
     * 刷新令牌。
     */
    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        StorePlatformPublisher publisher = resolvePublisher(storeConfig);
        return publisher != null ? publisher.refreshToken(storeConfig) : super.refreshToken(storeConfig);
    }

    /**
     * 提交发布。
     */
    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        StorePlatformPublisher publisher = resolvePublisher(storeConfig);
        return publisher != null
                ? publisher.submitRelease(storeConfig, version, record, token)
                : super.submitRelease(storeConfig, version, record, token);
    }

    /**
     * 查询审核。
     */
    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        StorePlatformPublisher publisher = resolvePublisher(storeConfig);
        return publisher != null
                ? publisher.queryReview(storeConfig, record, token)
                : super.queryReview(storeConfig, record, token);
    }

    /**
     * 解析Publisher。
     */
    private StorePlatformPublisher resolvePublisher(AppStoreConfig storeConfig) {
        for (StorePlatformPublisher storePlatformPublisher : storePlatformPublishers) {
            if (storePlatformPublisher.supports(storeConfig)) {
                return storePlatformPublisher;
            }
        }
        return null;
    }
}
