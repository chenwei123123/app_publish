package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.ReleaseStatus;
import com.app.publishservice.domain.enums.TokenType;
import com.app.publishservice.service.model.StoreReviewResult;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.app.publishservice.service.model.TokenPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class StoreLifecycleSupport {

    private static final Logger log = LoggerFactory.getLogger(StoreLifecycleSupport.class);

    private final RestClient restClient;
    private final AppProperties appProperties;
    private final StoreRequestSupport requestSupport;
    private final StoreMetadataSupport metadataSupport;
    private final StoreValueSupport valueSupport;

    StoreLifecycleSupport(
            RestClient restClient,
            AppProperties appProperties,
            StoreRequestSupport requestSupport,
            StoreMetadataSupport metadataSupport,
            StoreValueSupport valueSupport
    ) {
        this.restClient = restClient;
        this.appProperties = appProperties;
        this.requestSupport = requestSupport;
        this.metadataSupport = metadataSupport;
        this.valueSupport = valueSupport;
    }

    TokenPayload refreshToken(AppStoreConfig storeConfig) {
        log.debug("Start refresh token, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (StringUtils.hasText(storeConfig.getToken())) {
            log.debug("Use configured static token, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
            return new TokenPayload(TokenType.STATIC.getCode(), storeConfig.getToken(), LocalDateTime.now().plusYears(10));
        }
        if (endpoint.isMockEnabled() || !StringUtils.hasText(endpoint.getBaseUrl()) || !StringUtils.hasText(endpoint.getTokenEndpoint())) {
            log.info("Use mock token refresh, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
            return new TokenPayload(
                    TokenType.ACCESS_TOKEN.getCode(),
                    "mock-" + storeConfig.getStoreType().getCode() + "-" + UUID.randomUUID(),
                    LocalDateTime.now().plusHours(1)
            );
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountName", storeConfig.getAccountName());
        body.put("email", storeConfig.getEmail());
        body.put("phone", storeConfig.getPhone());
        body.put("clientId", storeConfig.getClientId());
        body.put("clientSecret", storeConfig.getClientSecret());
        body.put("publicKey", storeConfig.getPublicKey());
        body.put("privateKey", storeConfig.getPrivateKey());
        body.put("ipWhitelist", storeConfig.getIpWhitelist());

        String tokenUrl = endpoint.getBaseUrl() + endpoint.getTokenEndpoint();
        log.info("Request store token, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> response = requestSupport.executeStoreRequest(
                requestSupport.trace(storeConfig, "request token for " + storeConfig.getStoreType().getCode(), "POST", tokenUrl, null, body),
                "request token for " + storeConfig.getStoreType().getCode(),
                () -> restClient.post()
                        .uri(tokenUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class)
        );

        String token = valueSupport.firstString(response, "accessToken", "token");
        Number expiresIn = response.get("expiresIn") instanceof Number number ? number : 3600;
        return new TokenPayload(TokenType.ACCESS_TOKEN.getCode(), token, LocalDateTime.now().plusSeconds(expiresIn.longValue()));
    }

    StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        log.info(
                "Start submit release, storeType={}, versionId={}, appId={}, releaseType={}",
                storeConfig.getStoreType().getCode(),
                version.getId(),
                version.getAppId(),
                record == null ? null : record.getReleaseType()
        );
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("appId", version.getAppInfo().getId());
            payload.put("packageName", version.getAppInfo().getPackageName());
            payload.put("versionName", version.getVersionName());
            payload.put("versionCode", version.getVersionCode());
            payload.put("packageUrl", version.getPackageUrl());
            payload.put("updateLog", version.getUpdateLog());
            payload.put("reinforced", version.getIsReinforce() == 1);

            StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
            if (endpoint.isMockEnabled() || !StringUtils.hasText(endpoint.getBaseUrl()) || !StringUtils.hasText(endpoint.getSubmitEndpoint())) {
                log.info("Use mock submit release, storeType={}, versionId={}", storeConfig.getStoreType().getCode(), version.getId());
                String storeReleaseId = storeConfig.getStoreType().getCode() + "-" + UUID.randomUUID();
                return new StoreSubmitResult(
                        storeReleaseId,
                        metadataSupport.writeJson(payload),
                        "{\"accepted\":true,\"storeReleaseId\":\"" + storeReleaseId + "\"}",
                        "mock submit success"
                );
            }

            String submitUrl = endpoint.getBaseUrl() + endpoint.getSubmitEndpoint();
            log.info("Submit release to store endpoint, storeType={}, versionId={}", storeConfig.getStoreType().getCode(), version.getId());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = requestSupport.executeStoreRequest(
                    requestSupport.trace(
                            storeConfig,
                            "submit release to " + storeConfig.getStoreType().getCode(),
                            "POST",
                            submitUrl,
                            null,
                            requestSupport.requestPayload(payload, Map.of("Authorization", "Bearer " + token))
                    ),
                    "submit release to " + storeConfig.getStoreType().getCode(),
                    () -> restClient.post()
                            .uri(submitUrl)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .body(payload)
                            .retrieve()
                            .body(Map.class)
            );

            String storeReleaseId = valueSupport.firstString(response, "storeReleaseId", "id");
            return new StoreSubmitResult(storeReleaseId, metadataSupport.writeJson(payload), metadataSupport.writeJson(response), "submit success");
        }
    }

    StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        log.debug("Start query review, releaseId={}, storeType={}, releaseType={}", record.getId(), record.getStoreType(), record.getReleaseType());
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
            if (endpoint.isMockEnabled() || !StringUtils.hasText(endpoint.getBaseUrl()) || !StringUtils.hasText(endpoint.getStatusEndpoint())) {
                ReleaseStatus status = record.getReleaseTime() != null
                        && record.getReleaseTime().isBefore(LocalDateTime.now().minusSeconds(appProperties.getReviewAutoPassSeconds()))
                        ? ReleaseStatus.PASS
                        : ReleaseStatus.AUDITING;
                log.debug("Use mock review query, releaseId={}, storeType={}, status={}", record.getId(), record.getStoreType(), status.getCode());
                return new StoreReviewResult(status, "{\"mockStatus\":\"" + status.getCode() + "\"}", null);
            }

            String statusUrl = endpoint.getBaseUrl() + endpoint.getStatusEndpoint().replace("{id}", record.getStoreReleaseId());
            log.debug("Query review status, releaseId={}, storeType={}, statusUrl={}", record.getId(), record.getStoreType(), statusUrl);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = requestSupport.executeStoreRequest(
                    requestSupport.trace(
                            storeConfig,
                            "query review for " + storeConfig.getStoreType().getCode(),
                            "GET",
                            statusUrl,
                            Map.of("id", record.getStoreReleaseId()),
                            Map.of("Authorization", "Bearer " + token)
                    ),
                    "query review for " + storeConfig.getStoreType().getCode(),
                    () -> restClient.get()
                            .uri(statusUrl)
                            .header("Authorization", "Bearer " + token)
                            .retrieve()
                            .body(Map.class)
            );

            Object statusCode = response.get("status");
            ReleaseStatus releaseStatus = valueSupport.mapStatus(statusCode == null ? "auditing" : statusCode.toString());
            String rejectReason = response.get("rejectReason") == null ? null : response.get("rejectReason").toString();
            return new StoreReviewResult(releaseStatus, metadataSupport.writeJson(response), rejectReason);
        }
    }

    private StoreApiProperties.StoreEndpointProperties endpoint(AppStoreConfig storeConfig) {
        return appProperties.getStoreApi().getStore(storeConfig.getStoreType().getCode());
    }
}
