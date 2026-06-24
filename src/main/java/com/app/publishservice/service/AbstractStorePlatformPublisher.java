package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.ReleaseStatus;
import com.app.publishservice.service.model.StoreReviewResult;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.app.publishservice.service.model.TokenPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

abstract class AbstractStorePlatformPublisher implements StorePublisher {

    protected static final Logger log = LoggerFactory.getLogger(AbstractStorePlatformPublisher.class);

    protected final RestClient restClient;
    protected final ObjectMapper objectMapper;
    protected final AppProperties appProperties;
    protected final StoreRequestLogService storeRequestLogService;

    private final StoreRequestSupport requestSupport;
    private final StorePackageSupport packageSupport;
    private final StoreMetadataSupport metadataSupport;
    private final StoreValueSupport valueSupport;
    private final StoreLifecycleSupport lifecycleSupport;

    protected AbstractStorePlatformPublisher(RestClient restClient, ObjectMapper objectMapper, AppProperties appProperties) {
        this(restClient, objectMapper, appProperties, null);
    }

    @Autowired
    protected AbstractStorePlatformPublisher(
            RestClient restClient,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            StoreRequestLogService storeRequestLogService
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.storeRequestLogService = storeRequestLogService;

        this.requestSupport = new StoreRequestSupport(objectMapper, appProperties, storeRequestLogService);
        this.packageSupport = new StorePackageSupport(restClient, appProperties, requestSupport);
        this.metadataSupport = new StoreMetadataSupport(new PublishMetadataResolver(appProperties), objectMapper);
        this.valueSupport = new StoreValueSupport();
        this.lifecycleSupport = new StoreLifecycleSupport(restClient, appProperties, requestSupport, metadataSupport, valueSupport);
    }

    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        return lifecycleSupport.refreshToken(storeConfig);
    }

    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        return lifecycleSupport.submitRelease(storeConfig, version, record, token);
    }

    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        return lifecycleSupport.queryReview(storeConfig, record, token);
    }

    protected StoreApiProperties.StoreEndpointProperties endpoint(AppStoreConfig storeConfig) {
        return appProperties.getStoreApi().getStore(storeConfig.getStoreType().getCode());
    }

    protected <T> T executeStoreRequest(String action, Supplier<T> request) {
        return requestSupport.executeStoreRequest(action, request);
    }

    protected <T> T executeStoreRequest(String action, Supplier<T> request, Supplier<T> mockResponse) {
        return requestSupport.executeStoreRequest(null, action, request, mockResponse);
    }

    protected <T> T executeStoreRequest(StoreRequestTrace trace, Supplier<T> request) {
        return requestSupport.executeStoreRequest(trace, trace == null ? null : trace.action(), request);
    }

    protected <T> T executeStoreRequest(StoreRequestTrace trace, Supplier<T> request, Supplier<T> mockResponse) {
        return requestSupport.executeStoreRequest(trace, trace == null ? null : trace.action(), request, mockResponse);
    }

    protected <T> T executeStoreRequest(StoreRequestTrace trace, String action, Supplier<T> request) {
        return requestSupport.executeStoreRequest(trace, action, request);
    }

    protected <T> T executeStoreRequest(StoreRequestTrace trace, String action, Supplier<T> request, Supplier<T> mockResponse) {
        return requestSupport.executeStoreRequest(trace, action, request, mockResponse);
    }

    protected StoreRequestTrace trace(
            AppStoreConfig storeConfig,
            String action,
            String requestMethod,
            String requestUrl,
            Object requestParams,
            Object requestBody
    ) {
        return requestSupport.trace(storeConfig, action, requestMethod, requestUrl, requestParams, requestBody);
    }

    protected Map<String, Object> requestPayload(Object body, Map<String, Object> extras) {
        return requestSupport.requestPayload(body, extras);
    }

    protected ReleaseStatus mapStatus(String value) {
        return valueSupport.mapStatus(value);
    }

    protected String firstString(Map<String, Object> source, String... keys) {
        return valueSupport.firstString(source, keys);
    }

    protected String writeJson(Object object) {
        return metadataSupport.writeJson(object);
    }

    protected Path requireLocalPackage(AppVersion version) {
        return packageSupport.requireLocalPackage(version);
    }

    protected Path requireLocalPackage(String packageLocation, String emptyMessage) {
        return packageSupport.requireLocalPackage(packageLocation, emptyMessage);
    }

    protected String sha256Hex(Path path) {
        return packageSupport.sha256Hex(path);
    }

    protected String md5Hex(PackageContentSource packageSource) {
        return packageSupport.md5Hex(packageSource);
    }

    protected String hmacSha256Hex(String data, String key) {
        return packageSupport.hmacSha256Hex(data, key);
    }

    protected String toHex(byte[] bytes) {
        return packageSupport.toHex(bytes);
    }

    protected long fileSize(Path path) {
        return packageSupport.fileSize(path);
    }

    protected String decodeStoreResponseBody(String action, byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    protected ProjectMetadataContext resolveProjectMetadataContext(String packageLocation) {
        return metadataSupport.resolveProjectMetadataContext(packageLocation);
    }

    protected Path resolveProjectAssetPath(Path metadataPath, Object assetLocation) {
        return metadataSupport.resolveProjectAssetPath(metadataPath, assetLocation);
    }

    protected List<Path> resolveProjectAssetPaths(Path metadataPath, List<String> assetLocations) {
        return resolveProjectAssetPaths(metadataPath, assetLocations, "Asset file not found in project: ");
    }

    protected List<Path> resolveProjectAssetPaths(
            Path metadataPath,
            List<String> assetLocations,
            String assetNotFoundMessagePrefix
    ) {
        return metadataSupport.resolveProjectAssetPaths(metadataPath, assetLocations, assetNotFoundMessagePrefix);
    }

    protected Object metadataLookup(Map<String, Object> metadata, String sectionKey, String key) {
        return metadataSupport.metadataLookup(metadata, sectionKey, key);
    }

    protected List<String> firstList(Object... values) {
        return metadataSupport.firstList(values);
    }

    protected Integer firstInteger(Object... values) {
        return metadataSupport.firstInteger(values);
    }

    protected Map<String, Object> readJson(String body) {
        return metadataSupport.readJson(body);
    }

    protected Map<String, Object> readJsonIfPossible(String body) {
        return metadataSupport.readJsonIfPossible(body);
    }

    protected Map<String, Object> asMap(Object value) {
        return metadataSupport.asMap(value);
    }

    protected int intValue(Object value) {
        return metadataSupport.intValue(value);
    }

    protected long longValue(Object value, long defaultValue) {
        return metadataSupport.longValue(value, defaultValue);
    }

    protected boolean isStagedRelease(AppReleaseRecord record) {
        return record != null && record.getReleaseType() != null && record.getReleaseType() == 2L;
    }

    protected String resolvePackageDownloadUrl(String packageLocation, Path packagePath) {
        return packageSupport.resolvePackageDownloadUrl(packageLocation, packagePath);
    }

    protected Path downloadPackageToLocal(String downloadUrl, String fileName) {
        return packageSupport.downloadPackageToLocal(downloadUrl, fileName);
    }

    protected Object uploadResource(PackageContentSource packageSource) {
        return packageSupport.uploadResource(packageSource);
    }

    protected Path toPath(String value) {
        return packageSupport.toPath(value);
    }

    protected String inferFileName(String packageLocation) {
        return packageSupport.inferFileName(packageLocation);
    }

    protected String buildQueryString(Map<String, ?> params) {
        return packageSupport.buildQueryString(params);
    }

    protected String normalizeTitle(String appName, String packageName) {
        return valueSupport.normalizeTitle(appName, packageName);
    }

    protected String normalizeStageText(int minLength, int maxLength, String... candidates) {
        return valueSupport.normalizeStageText(minLength, maxLength, candidates);
    }

    protected String firstNonBlank(String... values) {
        return valueSupport.firstNonBlank(values);
    }

    protected Object firstNonNull(Object... values) {
        return valueSupport.firstNonNull(values);
    }

    protected record ProjectMetadataContext(
            Path metadataPath,
            Map<String, Object> metadata
    ) {
    }

    protected record PackageContentSource(
            String fileName,
            Path localPath,
            String remoteUrl
    ) {
        String sourceType() {
            return localPath != null ? "local" : "remote";
        }
    }

    protected record StoreRequestTrace(
            AppStoreConfig storeConfig,
            String action,
            String requestMethod,
            String requestUrl,
            Object requestParams,
            Object requestBody
    ) {
    }
}
