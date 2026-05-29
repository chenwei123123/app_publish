package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.domain.entity.AppApiTokenCache;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.domain.enums.TokenType;
import com.app.publishservice.repository.AppApiTokenCacheRepository;
import com.app.publishservice.service.model.TokenPayload;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final AppProperties appProperties;
    private final AppApiTokenCacheRepository tokenCacheRepository;
    private final StorePublisher storePublisher;

    public TokenService(AppProperties appProperties, AppApiTokenCacheRepository tokenCacheRepository, StorePublisher storePublisher) {
        this.appProperties = appProperties;
        this.tokenCacheRepository = tokenCacheRepository;
        this.storePublisher = storePublisher;
    }

    @Transactional
    public String getValidToken(AppStoreConfig storeConfig) {
        LocalDateTime threshold = LocalDateTime.now().plusSeconds(appProperties.getTokenRefreshAheadSeconds());
        String expectedTokenType = expectedTokenType(storeConfig);
        List<AppApiTokenCache> caches = tokenCacheRepository.selectList(
                Wrappers.<AppApiTokenCache>lambdaQuery()
                        .eq(AppApiTokenCache::getStoreConfigId, storeConfig.getId())
                        .eq(AppApiTokenCache::getTokenType, expectedTokenType)
                        .orderByDesc(AppApiTokenCache::getUpdateTime)
                        .last("limit 1")
        );
        AppApiTokenCache cache = caches.isEmpty() ? null : caches.getFirst();
        if (cache != null && cache.getExpireTime() != null && cache.getExpireTime().isAfter(threshold)) {
            log.debug("Use cached token, storeConfigId={}, storeType={}, expireTime={}", storeConfig.getId(), storeConfig.getStoreType().getCode(), cache.getExpireTime());
            return cache.getTokenValue();
        }

        log.info("Refresh token, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
        TokenPayload payload = storePublisher.refreshToken(storeConfig);
        AppApiTokenCache entity = cache == null ? new AppApiTokenCache() : cache;
        entity.setStoreConfigId(storeConfig.getId());
        entity.setStoreConfig(storeConfig);
        entity.setTokenType(payload.tokenType());
        entity.setTokenValue(payload.tokenValue());
        entity.setExpireTime(payload.expireTime());
        if (entity.getId() == null) {
            tokenCacheRepository.insert(entity);
        } else {
            tokenCacheRepository.updateById(entity);
        }
        log.info("Token refreshed, storeConfigId={}, storeType={}, expireTime={}", storeConfig.getId(), storeConfig.getStoreType().getCode(), payload.expireTime());
        return payload.tokenValue();
    }

    private String expectedTokenType(AppStoreConfig storeConfig) {
        if (storeConfig.getStoreType() == StoreType.VIVO应用商店 || StringUtils.hasText(storeConfig.getToken())) {
            return TokenType.STATIC.getCode();
        }
        return TokenType.ACCESS_TOKEN.getCode();
    }
}
