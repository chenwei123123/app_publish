package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.domain.entity.AppApiTokenCache;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.enums.TokenType;
import com.app.publishservice.repository.AppApiTokenCacheRepository;
import com.app.publishservice.service.model.TokenPayload;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final ConcurrentMap<String, Object> REFRESH_LOCKS = new ConcurrentHashMap<>();

    private final AppProperties appProperties;
    private final AppApiTokenCacheRepository tokenCacheRepository;
    private final StorePublisher storePublisher;

    /**
     * 初始化TokenService。
     */
    public TokenService(
            AppProperties appProperties,
            AppApiTokenCacheRepository tokenCacheRepository,
            StorePublisher storePublisher
    ) {
        this.appProperties = appProperties;
        this.tokenCacheRepository = tokenCacheRepository;
        this.storePublisher = storePublisher;
    }

    /**
     * 获取可用令牌。
     */
    @Transactional
    public String getValidToken(AppStoreConfig storeConfig) {
        String expectedTokenType = expectedTokenType(storeConfig);
        String lockKey = storeConfig.getId() + ":" + expectedTokenType;
        Object refreshLock = REFRESH_LOCKS.computeIfAbsent(lockKey, key -> new Object());
        synchronized (refreshLock) {
            LocalDateTime threshold = LocalDateTime.now().plusSeconds(appProperties.getTokenRefreshAheadSeconds());
            AppApiTokenCache cache = latestTokenCache(storeConfig.getId(), expectedTokenType);
            if (cache != null && cache.getExpireTime() != null && cache.getExpireTime().isAfter(threshold)) {
                log.debug(
                        "Use cached token, storeConfigId={}, storeType={}, expireTime={}",
                        storeConfig.getId(),
                        storeConfig.getStoreType().getCode(),
                        cache.getExpireTime()
                );
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
            saveTokenCache(entity, payload, storeConfig);
            log.info("Token refreshed, storeConfigId={}, storeType={}, expireTime={}", storeConfig.getId(), storeConfig.getStoreType().getCode(), payload.expireTime());
            return payload.tokenValue();
        }
    }

    /**
     * 获取预期令牌类型。
     */
    private String expectedTokenType(AppStoreConfig storeConfig) {
        String storeTypeCode = storeConfig.getStoreType() == null ? null : storeConfig.getStoreType().getCode();
        if ("vivo".equalsIgnoreCase(storeTypeCode)
                || "yingyongbao".equalsIgnoreCase(storeTypeCode)
                || "xiaomi".equalsIgnoreCase(storeTypeCode)
                || StringUtils.hasText(storeConfig.getToken())) {
            return TokenType.STATIC.getCode();
        }
        return TokenType.ACCESS_TOKEN.getCode();
    }

    private AppApiTokenCache latestTokenCache(Long storeConfigId, String tokenType) {
        List<AppApiTokenCache> caches = tokenCacheRepository.selectList(
                Wrappers.<AppApiTokenCache>lambdaQuery()
                        .eq(AppApiTokenCache::getStoreConfigId, storeConfigId)
                        .eq(AppApiTokenCache::getTokenType, tokenType)
                        .orderByDesc(AppApiTokenCache::getUpdateTime)
                        .last("limit 1")
        );
        return caches.isEmpty() ? null : caches.get(0);
    }

    private void saveTokenCache(AppApiTokenCache entity, TokenPayload payload, AppStoreConfig storeConfig) {
        if (entity.getId() != null) {
            tokenCacheRepository.updateById(entity);
            return;
        }
        try {
            tokenCacheRepository.insert(entity);
        } catch (DuplicateKeyException ex) {
            AppApiTokenCache existing = latestTokenCache(storeConfig.getId(), payload.tokenType());
            if (existing == null) {
                throw ex;
            }
            existing.setStoreConfig(storeConfig);
            existing.setTokenType(payload.tokenType());
            existing.setTokenValue(payload.tokenValue());
            existing.setExpireTime(payload.expireTime());
            tokenCacheRepository.updateById(existing);
        }
    }
}
