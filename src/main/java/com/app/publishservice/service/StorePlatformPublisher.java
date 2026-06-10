package com.app.publishservice.service;

import com.app.publishservice.domain.entity.AppStoreConfig;

interface StorePlatformPublisher extends StorePublisher {

    boolean supports(AppStoreConfig storeConfig);
}
