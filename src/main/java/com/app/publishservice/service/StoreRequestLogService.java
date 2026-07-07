package com.app.publishservice.service;

import com.app.publishservice.domain.entity.AppStoreRequestLog;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.repository.AppStoreRequestLogRepository;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class StoreRequestLogService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String MASK_VALUE = "***";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "access_token",
            "accesskey",
            "access_key",
            "api_sign",
            "apikey",
            "api_key",
            "apikeysecret",
            "api_secret",
            "authorization",
            "client_secret",
            "private_key",
            "mi_private_key",
            "refresh_token",
            "secret",
            "sign",
            "token",
            "token_value"
    );

    private final AppStoreRequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * 初始化StoreRequestLogService。
     */
    public StoreRequestLogService(AppStoreRequestLogRepository requestLogRepository, ObjectMapper objectMapper) {
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理日志 Success相关逻辑。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logSuccess(
            AppStoreConfig storeConfig,
            String action,
            String requestMethod,
            String requestUrl,
            Object requestParams,
            Object requestBody,
            Integer responseStatusCode,
            Object responseBody,
            long durationMs
    ) {
        save(
                storeConfig,
                action,
                requestMethod,
                requestUrl,
                requestParams,
                requestBody,
                responseStatusCode,
                responseBody,
                STATUS_SUCCESS,
                null,
                durationMs
        );
    }

    /**
     * 处理日志 Failure相关逻辑。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailure(
            AppStoreConfig storeConfig,
            String action,
            String requestMethod,
            String requestUrl,
            Object requestParams,
            Object requestBody,
            Integer responseStatusCode,
            Object responseBody,
            String errorMessage,
            long durationMs
    ) {
        save(
                storeConfig,
                action,
                requestMethod,
                requestUrl,
                requestParams,
                requestBody,
                responseStatusCode,
                responseBody,
                STATUS_FAILED,
                errorMessage,
                durationMs
        );
    }

    /**
     * 保存相关数据。
     */
    private void save(
            AppStoreConfig storeConfig,
            String action,
            String requestMethod,
            String requestUrl,
            Object requestParams,
            Object requestBody,
            Integer responseStatusCode,
            Object responseBody,
            String requestStatus,
            String errorMessage,
            long durationMs
    ) {
        if (storeConfig == null || storeConfig.getId() == null || storeConfig.getStoreType() == null || !StringUtils.hasText(action)) {
            return;
        }

        Long releaseRecordId = StoreRequestLogContextHolder.currentReleaseRecordId();
        AppStoreRequestLog log = new AppStoreRequestLog();
        log.setReleaseRecordId(releaseRecordId);
        log.setStoreConfigId(storeConfig.getId());
        log.setStoreType(storeConfig.getStoreType());
        log.setAction(action);
        log.setRequestOrder(nextRequestOrder(releaseRecordId));
        log.setRequestMethod(requestMethod);
        log.setRequestUrl(requestUrl);
        log.setRequestParams(stringify(requestParams));
        log.setRequestBody(stringify(requestBody));
        log.setResponseStatusCode(responseStatusCode);
        log.setResponseBody(stringify(responseBody));
        log.setRequestStatus(requestStatus);
        log.setErrorMessage(limitLength(errorMessage, 1000));
        log.setDurationMs(durationMs);
        requestLogRepository.insert(log);
    }

    /**
     * 获取下一个请求 Order。
     */
    private Long nextRequestOrder(Long releaseRecordId) {
        if (releaseRecordId == null) {
            return null;
        }
        AppStoreRequestLog latest = requestLogRepository.selectOne(
                Wrappers.<AppStoreRequestLog>lambdaQuery()
                        .eq(AppStoreRequestLog::getReleaseRecordId, releaseRecordId)
                        .orderByDesc(AppStoreRequestLog::getRequestOrder)
                        .orderByDesc(AppStoreRequestLog::getId)
                        .last("limit 1")
        );
        if (latest == null || latest.getRequestOrder() == null) {
            return 1L;
        }
        return latest.getRequestOrder() + 1;
    }

    /**
     * 转换相关数据。
     */
    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return sanitizeString(text);
        }
        try {
            return objectMapper.writeValueAsString(sanitizeValue(value));
        } catch (JsonProcessingException ex) {
            return limitLength(String.valueOf(value), 10000);
        }
    }

    /**
     * 处理sanitize 字符串相关逻辑。
     */
    private String sanitizeString(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (!looksLikeJson(trimmed)) {
            return limitLength(value, 10000);
        }
        try {
            Object parsed = objectMapper.readValue(trimmed, new TypeReference<>() {
            });
            return objectMapper.writeValueAsString(sanitizeValue(parsed));
        } catch (JsonProcessingException ex) {
            return limitLength(value, 10000);
        }
    }

    /**
     * 判断Like JSON。
     */
    private boolean looksLikeJson(String value) {
        return (value.startsWith("{") && value.endsWith("}"))
                || (value.startsWith("[") && value.endsWith("]"));
    }

    /**
     * 处理sanitize 值相关逻辑。
     */
    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String normalizedKey = key == null ? "" : normalizeKey(String.valueOf(key));
                sanitized.put(String.valueOf(key), isSensitiveKey(normalizedKey) ? MASK_VALUE : sanitizeValue(item));
            });
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterable) {
                sanitized.add(sanitizeValue(item));
            }
            return sanitized;
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value == null) {
            return value;
        }
        return limitLength(String.valueOf(value), 10000);
    }

    /**
     * 判断是否Sensitive Key。
     */
    private boolean isSensitiveKey(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        if (SENSITIVE_KEYS.contains(key)) {
            return true;
        }
        return key.contains("token") || key.contains("secret") || key.contains("sign") || key.contains("authorization");
    }

    /**
     * 规范化Key。
     */
    private String normalizeKey(String key) {
        return key.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    /**
     * 处理limit Length相关逻辑。
     */
    private String limitLength(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
