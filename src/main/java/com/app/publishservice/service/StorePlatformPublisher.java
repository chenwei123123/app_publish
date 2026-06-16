package com.app.publishservice.service;

import com.app.publishservice.domain.entity.AppStoreConfig;

interface StorePlatformPublisher extends StorePublisher {

    /**
     * 判断是否支持相关数据。
     */
    boolean supports(AppStoreConfig storeConfig);
}
