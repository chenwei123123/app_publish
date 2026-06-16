package com.app.publishservice.service;

import com.app.publishservice.common.exception.StoreApiException;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class YingyongbaoStorePlatformPublisher extends AbstractStorePlatformPublisher implements StorePlatformPublisher {

    private static final String YINGYONGBAO_BASE_URL = "https://p.open.qq.com/open_file/developer_api";
    private static final String YINGYONGBAO_QUERY_APP_DETAIL_ENDPOINT = "/query_app_detail";
    private static final String YINGYONGBAO_UPLOAD_INFO_ENDPOINT = "/get_file_upload_info";
    private static final String YINGYONGBAO_UPDATE_APP_ENDPOINT = "/update_app";
    private static final String YINGYONGBAO_AUDIT_STATUS_ENDPOINT = "/query_app_update_status";

    YingyongbaoStorePlatformPublisher(
            RestClient restClient,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            StoreRequestLogService storeRequestLogService
    ) {
        super(restClient, objectMapper, appProperties, storeRequestLogService);
    }

    @Override
    public boolean supports(AppStoreConfig storeConfig) {
        return storeConfig != null
                && storeConfig.getStoreType() != null
                && "yingyongbao".equalsIgnoreCase(storeConfig.getStoreType().getCode());
    }

    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        String marker = StringUtils.hasText(storeConfig.getClientId()) ? storeConfig.getClientId() : "yingyongbao-static";
        return new TokenPayload(TokenType.STATIC.getCode(), marker, LocalDateTime.now().plusYears(10));
    }

    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return submitYingyongbaoRelease(storeConfig, version);
        }
    }

    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return queryYingyongbaoReview(storeConfig, record);
        }
    }

    private StoreSubmitResult submitYingyongbaoRelease(AppStoreConfig storeConfig, AppVersion version) {
        YingyongbaoContext context = resolveYingyongbaoContext(version, null);
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);

        Map<String, Object> appDetailResponse = yingyongbaoSignedRequest(
                storeConfig,
                yingyongbaoQueryAppDetailEndpoint(endpoint),
                Map.of("pkg_name", context.packageName(), "app_id", context.appId()),
                "query yingyongbao app detail"
        );

        List<Map<String, Object>> uploadLogs = new ArrayList<>();
        Map<String, Object> updatePayload = buildYingyongbaoUpdatePayload(storeConfig, version, context, uploadLogs);
        Map<String, Object> updateResponse = yingyongbaoSignedRequest(
                storeConfig,
                yingyongbaoUpdateAppEndpoint(endpoint),
                updatePayload,
                "update yingyongbao app"
        );

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("queryAppDetail", Map.of("pkg_name", context.packageName(), "app_id", context.appId()));
        requestLog.put("uploads", uploadLogs.stream().map(item -> item.get("request")).toList());
        requestLog.put("updateApp", updatePayload);

        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("queryAppDetail", appDetailResponse);
        responseLog.put("uploads", uploadLogs.stream().map(item -> item.get("response")).toList());
        responseLog.put("updateApp", updateResponse);

        return new StoreSubmitResult(
                context.appId(),
                writeJson(requestLog),
                writeJson(responseLog),
                firstNonBlank(firstString(updateResponse, "msg", "message"), "submit success")
        );
    }

    private StoreReviewResult queryYingyongbaoReview(AppStoreConfig storeConfig, AppReleaseRecord record) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        String packageName = resolveYingyongbaoPackageName(record);
        String appId = resolveYingyongbaoAppId(record);

        Map<String, Object> response = yingyongbaoSignedRequest(
                storeConfig,
                yingyongbaoAuditStatusEndpoint(endpoint),
                Map.of("pkg_name", packageName, "app_id", appId),
                "query yingyongbao audit status"
        );
        int auditStatus = intValue(response.get("audit_status"));
        ReleaseStatus releaseStatus = mapYingyongbaoAuditStatus(auditStatus);
        String rejectReason = releaseStatus == ReleaseStatus.REJECT
                ? firstString(response, "audit_reason", "msg", "message")
                : null;
        return new StoreReviewResult(releaseStatus, writeJson(response), StringUtils.hasText(rejectReason) ? rejectReason : null);
    }

    private Map<String, Object> buildYingyongbaoUpdatePayload(
            AppStoreConfig storeConfig,
            AppVersion version,
            YingyongbaoContext context,
            List<Map<String, Object>> uploadLogs
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pkg_name", context.packageName());
        payload.put("app_id", context.appId());
        payload.put("deploy_type", resolveDeployType(context.metadata()));

        Object deployTime = yingyongbaoMetadata(context.metadata(), "deployTime");
        if (stringValue(payload.get("deploy_type")).equals("2")) {
            if (deployTime == null || !StringUtils.hasText(String.valueOf(deployTime))) {
                throw new IllegalStateException("Yingyongbao timed publish requires app.publish-metadata.values.yingyongbao.deployTime in application.yml.");
            }
            payload.put("deploy_time", deployTime);
        }




        Path apk32Path = resolveYingyongbaoPackagePath(
                context.metadataContext(),
                context.metadata(),
                "apk32Path",
                firstNonBlank(version.getPackageUrl32(), version.getPackageUrl())
        );
        Path apk64Path = resolveYingyongbaoPackagePath(
                context.metadataContext(),
                context.metadata(),
                "apk64Path",
                version.getPackageUrl64()
        );
        if (apk64Path != null && apk32Path != null && apk64Path.equals(apk32Path)) {
            apk64Path = null;
        }

        if (apk32Path != null) {
            YingyongbaoUploadResult uploadResult = uploadYingyongbaoFile(storeConfig, context, apk32Path, "apk");
            payload.put("apk32_flag", 1);
            payload.put("apk32_file_serial_number", uploadResult.serialNumber());
            payload.put("apk32_file_md5", md5Hex(new PackageContentSource(apk32Path.getFileName().toString(), apk32Path, null)));
            uploadLogs.add(uploadResult.logEntry());
        } else {
            payload.put("apk32_flag", 2);
        }

        if (apk64Path != null) {
            YingyongbaoUploadResult uploadResult = uploadYingyongbaoFile(storeConfig, context, apk64Path, "apk");
            payload.put("apk64_flag", 1);
            payload.put("apk64_file_serial_number", uploadResult.serialNumber());
            payload.put("apk64_file_md5", md5Hex(new PackageContentSource(apk64Path.getFileName().toString(), apk64Path, null)));
            uploadLogs.add(uploadResult.logEntry());
        } else {
            payload.put("apk64_flag", 2);
        }

        return payload;
    }

    private YingyongbaoUploadResult uploadYingyongbaoFile(
            AppStoreConfig storeConfig,
            YingyongbaoContext context,
            Path filePath,
            String fileType
    ) {
        Map<String, Object> uploadInfoRequest = new LinkedHashMap<>();
        uploadInfoRequest.put("pkg_name", context.packageName());
        uploadInfoRequest.put("app_id", context.appId());
        uploadInfoRequest.put("file_type", fileType);
        uploadInfoRequest.put("file_name", filePath.getFileName().toString());

        Map<String, Object> uploadInfoResponse = yingyongbaoSignedRequest(
                storeConfig,
                yingyongbaoUploadInfoEndpoint(endpoint(storeConfig)),
                uploadInfoRequest,
                "get yingyongbao upload info"
        );
        String preSignUrl = firstString(uploadInfoResponse, "pre_sign_url", "preSignUrl");
        String serialNumber = firstString(uploadInfoResponse, "serial_number", "serialNumber");
        if (!StringUtils.hasText(preSignUrl) || !StringUtils.hasText(serialNumber)) {
            throw new IllegalStateException("Yingyongbao upload info response is missing pre_sign_url or serial_number");
        }

        if (!endpoint(storeConfig).isMockEnabled()) {
            executeStoreRequest(
                    trace(
                            storeConfig,
                            "upload yingyongbao file " + filePath.getFileName(),
                            "PUT",
                            preSignUrl,
                            null,
                            Map.of("fileType", fileType, "fileName", filePath.getFileName().toString())
                    ),
                    () -> restClient.put()
                            .uri(preSignUrl)
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(new FileSystemResource(filePath))
                            .retrieve()
                            .toBodilessEntity()
            );
        }

        Map<String, Object> uploadResponse = Map.of("status", "uploaded", "serial_number", serialNumber, "file_name", filePath.getFileName().toString());
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("request", uploadInfoRequest);
        logEntry.put("response", Map.of("uploadInfo", uploadInfoResponse, "upload", uploadResponse));
        return new YingyongbaoUploadResult(serialNumber, logEntry);
    }

    private Map<String, Object> yingyongbaoSignedRequest(
            AppStoreConfig storeConfig,
            String endpointPath,
            Map<String, Object> businessParams,
            String action
    ) {
        if (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Yingyongbao request requires clientId as user_id and clientSecret as access_secret");
        }
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            return mockYingyongbaoSignedResponse(endpointPath, businessParams);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", storeConfig.getClientId().trim());
        payload.put("timestamp", Instant.now().getEpochSecond());
        if (businessParams != null && !businessParams.isEmpty()) {
            payload.putAll(businessParams);
        }
        payload.put("sign", signYingyongbaoPayload(payload, storeConfig.getClientSecret().trim()));

        String url = yingyongbaoBaseUrl(endpoint) + endpointPath;
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        payload.forEach((key, value) -> {
            if (value != null) {
                body.add(key, String.valueOf(value));
            }
        });

        String responseBody = executeStoreRequest(
                trace(storeConfig, action, "POST", url, null, payload),
                () -> restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(body)
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        ensureYingyongbaoSuccess(response, action);
        return response;
    }

    private Map<String, Object> mockYingyongbaoSignedResponse(String endpointPath, Map<String, Object> businessParams) {
        Map<String, Object> params = businessParams == null ? Map.of() : businessParams;
        if (matchesYingyongbaoMockEndpoint(endpointPath, YINGYONGBAO_QUERY_APP_DETAIL_ENDPOINT)) {
            return Map.of(
                    "ret", 0,
                    "msg", "success",
                    "app_id", firstNonBlank(stringValue(params.get("app_id")), "mock-app-id"),
                    "pkg_name", firstNonBlank(stringValue(params.get("pkg_name")), "mock.pkg.name")
            );
        }
        if (YINGYONGBAO_UPLOAD_INFO_ENDPOINT.equals(endpointPath)) {
            String fileName = firstNonBlank(stringValue(params.get("file_name")), "mock.apk");
            return Map.of(
                    "ret", 0,
                    "msg", "success",
                    "pre_sign_url", "https://mock.yingyongbao.local/upload/" + fileName,
                    "serial_number", "mock-yyb-" + UUID.randomUUID()
            );
        }
        if (matchesYingyongbaoMockEndpoint(endpointPath, YINGYONGBAO_UPDATE_APP_ENDPOINT)) {
            return Map.of(
                    "ret", 0,
                    "msg", "success",
                    "app_id", firstNonBlank(stringValue(params.get("app_id")), "mock-app-id")
            );
        }
        if (matchesYingyongbaoMockEndpoint(endpointPath, YINGYONGBAO_AUDIT_STATUS_ENDPOINT)) {
            return Map.of(
                    "ret", 0,
                    "msg", "success",
                    "audit_status", 3
            );
        }
        throw new IllegalStateException("Unsupported Yingyongbao mock endpoint: " + endpointPath);
    }

    private boolean matchesYingyongbaoMockEndpoint(String endpointPath, String expectedEndpoint) {
        return StringUtils.hasText(endpointPath)
                && StringUtils.hasText(expectedEndpoint)
                && (expectedEndpoint.equals(endpointPath) || endpointPath.endsWith(expectedEndpoint));
    }

    private String signYingyongbaoPayload(Map<String, Object> payload, String accessSecret) {
        List<String> pairs = new ArrayList<>();
        payload.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !"sign".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> pairs.add(entry.getKey() + "=" + entry.getValue()));
        return hmacSha256Hex(String.join("&", pairs), accessSecret);
    }

    private YingyongbaoContext resolveYingyongbaoContext(AppVersion version, AppReleaseRecord record) {
        String packageName = resolveYingyongbaoPackageName(version, record);
        String packageLocation = firstNonBlank(version == null ? null : version.getPackageUrl64(), version == null ? null : version.getPackageUrl32(), version == null ? null : version.getPackageUrl());
        ProjectMetadataContext metadataContext = resolveProjectMetadataContext(packageLocation);
        Map<String, Object> metadata = metadataContext.metadata();
        String appId = firstNonBlank(
                record == null ? null : record.getStoreReleaseId(),
                stringValue(yingyongbaoMetadata(metadata, "appId"))
        );
        if (!StringUtils.hasText(appId)) {
            throw new IllegalStateException("Yingyongbao submit requires app.publish-metadata.values.yingyongbao.appId in application.yml.");
        }
        return new YingyongbaoContext(appId.trim(), packageName, metadataContext, metadata);
    }

    private String resolveYingyongbaoPackageName(AppReleaseRecord record) {
        return resolveYingyongbaoPackageName(null, record);
    }

    private String resolveYingyongbaoPackageName(AppVersion version, AppReleaseRecord record) {
        String packageName = record == null ? null : record.getPackageName();
        if (!StringUtils.hasText(packageName) && version != null && version.getAppInfo() != null) {
            packageName = version.getAppInfo().getPackageName();
        }
        if (!StringUtils.hasText(packageName)) {
            throw new IllegalArgumentException("Yingyongbao request requires packageName");
        }
        return packageName.trim();
    }

    private String resolveYingyongbaoAppId(AppReleaseRecord record) {
        if (record != null && StringUtils.hasText(record.getStoreReleaseId())) {
            return record.getStoreReleaseId().trim();
        }
        if (record != null && record.getAppVersion() != null) {
            return resolveYingyongbaoContext(record.getAppVersion(), record).appId();
        }
        throw new IllegalStateException("Yingyongbao review query requires storeReleaseId as appId");
    }

    private Path resolveYingyongbaoPackagePath(
            ProjectMetadataContext metadataContext,
            Map<String, Object> metadata,
            String metadataKey,
            String defaultLocation
    ) {
        if (StringUtils.hasText(defaultLocation)) {
            return requireLocalPackage(defaultLocation, "Yingyongbao package path is empty");
        }
        Object configuredLocation = yingyongbaoMetadata(metadata, metadataKey);
        if (configuredLocation == null || !StringUtils.hasText(String.valueOf(configuredLocation))) {
            return null;
        }
        Path resolvedPath = resolveProjectAssetPath(metadataContext.metadataPath(), configuredLocation);
        if (resolvedPath != null) {
            return resolvedPath;
        }
        return requireLocalPackage(String.valueOf(configuredLocation), "Yingyongbao package path is empty");
    }

    private Object yingyongbaoMetadata(Map<String, Object> metadata, String key) {
        return firstNonNull(
                metadataLookup(metadata, "yingyongbao", key),
                metadataLookup(metadata, null, "yingyongbao" + Character.toUpperCase(key.charAt(0)) + key.substring(1)),
                metadataLookup(metadata, null, key)
        );
    }

    private int resolveDeployType(Map<String, Object> metadata) {
        Integer deployType = firstInteger(yingyongbaoMetadata(metadata, "deployType"));
        return deployType == null ? 1 : deployType;
    }

    private ReleaseStatus mapYingyongbaoAuditStatus(int auditStatus) {
        return switch (auditStatus) {
            case 2 -> ReleaseStatus.REJECT;
            case 3 -> ReleaseStatus.PASS;
            case 8 -> ReleaseStatus.OFFLINE;
            default -> ReleaseStatus.AUDITING;
        };
    }

    private void ensureYingyongbaoSuccess(Map<String, Object> response, String action) {
        int ret = intValue(response.get("ret"));
        if (ret == 0) {
            return;
        }
        String message = firstNonBlank(firstString(response, "msg", "message"), "unknown error");
        throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Yingyongbao " + action + " failed: ret=" + ret + ", msg=" + message);
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && !StringUtils.hasText(text)) {
            return;
        }
        target.put(key, value);
    }

    private String stringifyJsonIfNeeded(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text) ? text.trim() : null;
        }
        return writeJson(value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String yingyongbaoBaseUrl(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getBaseUrl()) ? endpoint.getBaseUrl() : YINGYONGBAO_BASE_URL;
    }

    private String yingyongbaoQueryAppDetailEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getTokenEndpoint()) ? endpoint.getTokenEndpoint() : YINGYONGBAO_QUERY_APP_DETAIL_ENDPOINT;
    }

    private String yingyongbaoUploadInfoEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return YINGYONGBAO_UPLOAD_INFO_ENDPOINT;
    }

    private String yingyongbaoUpdateAppEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getSubmitEndpoint()) ? endpoint.getSubmitEndpoint() : YINGYONGBAO_UPDATE_APP_ENDPOINT;
    }

    private String yingyongbaoAuditStatusEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getStatusEndpoint()) ? endpoint.getStatusEndpoint() : YINGYONGBAO_AUDIT_STATUS_ENDPOINT;
    }

    private record YingyongbaoContext(
            String appId,
            String packageName,
            ProjectMetadataContext metadataContext,
            Map<String, Object> metadata
    ) {
    }

    private record YingyongbaoUploadResult(
            String serialNumber,
            Map<String, Object> logEntry
    ) {
    }
}
