package com.app.publishservice.service;

import com.app.publishservice.common.exception.StoreApiException;
import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.domain.entity.AppInfo;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class OppoStorePlatformPublisher extends AbstractStorePlatformPublisher implements StorePlatformPublisher {

    private static final String OPPO_BASE_URL = "https://oop-openapi-cn.heytapmobi.com";
    private static final String OPPO_TOKEN_ENDPOINT = "/developer/v1/token";
    private static final String OPPO_UPLOAD_CONFIG_ENDPOINT = "/resource/v1/upload/get-upload-url";
    private static final String OPPO_SUBMIT_ENDPOINT = "/resource/v1/app/upd";
    private static final String OPPO_STATUS_ENDPOINT = "/resource/v1/app/task-state";
    private static final String OPPO_INFO_ENDPOINT = "/resource/v1/app/info";
    private static final long OPPO_TOKEN_EXPIRE_SECONDS = 48 * 60 * 60L;

    OppoStorePlatformPublisher(
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
                && "oppo".equalsIgnoreCase(storeConfig.getStoreType().getCode());
    }

    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            log.info("Use mock oppo token refresh, storeConfigId={}", storeConfig.getId());
            return new TokenPayload(
                    TokenType.ACCESS_TOKEN.getCode(),
                    "mock-oppo-" + UUID.randomUUID(),
                    LocalDateTime.now().plusSeconds(OPPO_TOKEN_EXPIRE_SECONDS)
            );
        }
        if (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Oppo token refresh requires clientId and clientSecret");
        }

        Map<String, Object> queryParams = Map.of(
                "client_id", storeConfig.getClientId(),
                "client_secret", storeConfig.getClientSecret()
        );
        String tokenUrl = oppoBaseUrl(endpoint) + oppoTokenEndpoint(endpoint);
        String responseBody = executeStoreRequest(
                trace(storeConfig, "refresh oppo token", "GET", tokenUrl, queryParams, null),
                () -> restClient.get()
                        .uri(tokenUrl + "?" + buildQueryString(queryParams))
                        .retrieve()
                        .body(String.class)
        );

        Map<String, Object> response = readJson(responseBody);
        ensureOppoSuccess(response, "refresh token");
        Map<String, Object> data = asMap(response.get("data"));
        String token = firstNonBlank(
                firstString(data, "access_token", "accessToken", "token"),
                firstString(response, "access_token", "accessToken", "token")
        );
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("Oppo token response does not contain access_token");
        }
        long expiresIn = longValue(
                firstNonNull(
                        data.get("expire_in"),
                        data.get("expires_in"),
                        data.get("expiresIn"),
                        response.get("expire_in"),
                        response.get("expires_in"),
                        response.get("expiresIn")
                ),
                OPPO_TOKEN_EXPIRE_SECONDS
        );
        return new TokenPayload(TokenType.ACCESS_TOKEN.getCode(), token, LocalDateTime.now().plusSeconds(expiresIn));
    }

    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return submitOppoRelease(storeConfig, version, token);
        }
    }

    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return queryOppoReview(storeConfig, record, token);
        }
    }

    private StoreSubmitResult submitOppoRelease(AppStoreConfig storeConfig, AppVersion version, String token) {
        AppInfo appInfo = version.getAppInfo();
        if (appInfo == null || !StringUtils.hasText(appInfo.getPackageName())) {
            throw new IllegalArgumentException("Oppo submit requires app packageName");
        }
        if (version.getVersionCode() == null) {
            throw new IllegalArgumentException("Oppo submit requires versionCode");
        }

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        Map<String, Object> submitPayload = new LinkedHashMap<>();
        submitPayload.put("pkg_name", appInfo.getPackageName());
        submitPayload.put("version_code", version.getVersionCode());

        if (endpoint.isMockEnabled()) {
            String storeReleaseId = "oppo-" + UUID.randomUUID();
            Map<String, Object> responseLog = Map.of(
                    "uploadConfig", Map.of("errno", 0, "data", Map.of("upload_url", "mock-upload-url")),
                    "upload", Map.of("errno", 0, "data", Map.of("apk_url", "mock-apk-url")),
                    "submit", Map.of("errno", 0, "data", Map.of("task_id", storeReleaseId))
            );
            submitPayload.put("apk_url", "mock-apk-url");
            return new StoreSubmitResult(storeReleaseId, writeJson(submitPayload), writeJson(responseLog), "mock submit success");
        }

        Path packagePath = requireLocalPackage(version);
        Map<String, Object> uploadConfigResponse = oppoSignedRequest(storeConfig, token, oppoUploadConfigEndpoint(endpoint), Map.of(), true);
        Map<String, Object> uploadConfigData = asMap(uploadConfigResponse.get("data"));
        Map<String, Object> uploadResult = uploadOppoPackage(storeConfig, uploadConfigData, packagePath);
        String apkUrl = firstNonBlank(
                firstString(uploadResult, "apk_url", "apkUrl", "file_url", "fileUrl"),
                firstString(uploadConfigData, "apk_url", "apkUrl", "file_url", "fileUrl", "upload_url", "uploadUrl")
        );
        if (!StringUtils.hasText(apkUrl)) {
            throw new IllegalStateException("Oppo upload succeeded but apk_url is missing");
        }

        submitPayload.put("apk_url", apkUrl);
        Map<String, Object> submitResponse = oppoSignedRequest(storeConfig, token, oppoSubmitEndpoint(endpoint), submitPayload, false);
        Map<String, Object> submitData = asMap(submitResponse.get("data"));
        String storeReleaseId = firstNonBlank(
                firstString(submitData, "task_id", "taskId", "id"),
                appInfo.getPackageName() + ":" + version.getVersionCode()
        );

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("uploadConfig", uploadConfigData);
        requestLog.put("submit", submitPayload);
        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("uploadConfig", uploadConfigResponse);
        responseLog.put("upload", uploadResult);
        responseLog.put("submit", submitResponse);
        return new StoreSubmitResult(storeReleaseId, writeJson(requestLog), writeJson(responseLog), "submit success");
    }

    private StoreReviewResult queryOppoReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        Map<String, Object> queryParams = buildOppoReviewQuery(record);
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            Map<String, Object> mockResponse = Map.of(
                    "appInfo", Map.of("errno", 0, "data", Map.of("audit_status_name", "审核通过", "audit_status", 2)),
                    "taskState", Map.of("errno", 0, "data", Map.of("task_state", 2))
            );
            return new StoreReviewResult(ReleaseStatus.PASS, writeJson(mockResponse), null);
        }

        Map<String, Object> appInfoResponse = oppoSignedRequest(storeConfig, token, oppoInfoEndpoint(endpoint), queryParams, true);
        Map<String, Object> appInfoData = asMap(appInfoResponse.get("data"));

        Map<String, Object> taskStateResponse = Map.of();
        Map<String, Object> taskStateData = Map.of();
        try {
            taskStateResponse = oppoSignedRequest(storeConfig, token, oppoStatusEndpoint(endpoint), queryParams, false);
            taskStateData = asMap(taskStateResponse.get("data"));
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to query oppo task state, releaseId={}, packageName={}, versionCode={}",
                    record.getId(),
                    queryParams.get("pkg_name"),
                    queryParams.get("version_code"),
                    ex
            );
        }

        ReleaseStatus releaseStatus = mapOppoStatus(appInfoData, taskStateData);
        String rejectReason = firstNonBlank(
                firstString(taskStateData, "err_msg", "errMsg"),
                firstString(appInfoData, "err_msg", "errMsg"),
                isRejectStatus(appInfoData) ? firstString(appInfoData, "audit_status_name", "auditStatusName") : ""
        );

        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("appInfo", appInfoResponse);
        if (!taskStateResponse.isEmpty()) {
            responseLog.put("taskState", taskStateResponse);
        }
        return new StoreReviewResult(releaseStatus, writeJson(responseLog), StringUtils.hasText(rejectReason) ? rejectReason : null);
    }

    private Map<String, Object> buildOppoReviewQuery(AppReleaseRecord record) {
        String packageName = record.getPackageName();
        if (!StringUtils.hasText(packageName) && record.getAppInfo() != null) {
            packageName = record.getAppInfo().getPackageName();
        }
        if (!StringUtils.hasText(packageName) && record.getAppVersion() != null && record.getAppVersion().getAppInfo() != null) {
            packageName = record.getAppVersion().getAppInfo().getPackageName();
        }
        if (!StringUtils.hasText(packageName)) {
            throw new IllegalArgumentException("Oppo review query requires packageName");
        }

        String versionCode = record.getVersionCode();
        if (versionCode == null && record.getAppVersion() != null) {
            versionCode = record.getAppVersion().getVersionCode();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pkg_name", packageName);
        if (versionCode != null) {
            payload.put("version_code", versionCode);
        }
        return payload;
    }

    private Map<String, Object> uploadOppoPackage(AppStoreConfig storeConfig, Map<String, Object> uploadConfigData, Path packagePath) {
        String uploadUrl = firstString(uploadConfigData, "upload_url", "uploadUrl");
        if (!StringUtils.hasText(uploadUrl)) {
            throw new IllegalStateException("Oppo upload config does not contain upload_url");
        }

        Map<String, Object> uploadForm = new LinkedHashMap<>(uploadConfigData);
        uploadForm.remove("upload_url");
        uploadForm.remove("uploadUrl");
        uploadForm.remove("apk_url");
        uploadForm.remove("apkUrl");
        uploadForm.remove("file_url");
        uploadForm.remove("fileUrl");

        String responseBody;
        if (uploadForm.isEmpty()) {
            responseBody = executeStoreRequest(
                    trace(
                            storeConfig,
                            "upload oppo package",
                            "PUT",
                            uploadUrl,
                            null,
                            Map.of("fileName", packagePath.getFileName().toString(), "contentType", MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    ),
                    () -> restClient.put()
                            .uri(uploadUrl)
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(new FileSystemResource(packagePath))
                            .retrieve()
                            .body(String.class)
            );
        } else {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            uploadForm.forEach((key, value) -> {
                if (value != null) {
                    body.add(key, String.valueOf(value));
                }
            });
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            fileHeaders.setContentDispositionFormData("file", packagePath.getFileName().toString());
            body.add("file", new HttpEntity<>(new FileSystemResource(packagePath), fileHeaders));
            responseBody = executeStoreRequest(
                    trace(
                            storeConfig,
                            "upload oppo package",
                            "POST",
                            uploadUrl,
                            null,
                            requestPayload(uploadForm, Map.of("fileName", packagePath.getFileName().toString(), "contentType", MediaType.MULTIPART_FORM_DATA_VALUE))
                    ),
                    () -> restClient.post()
                            .uri(uploadUrl)
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(body)
                            .retrieve()
                            .body(String.class)
            );
        }

        Map<String, Object> uploadResponse = readJsonIfPossible(responseBody);
        if (!uploadResponse.isEmpty()) {
            ensureOppoSuccess(uploadResponse, "upload apk");
            Map<String, Object> uploadData = asMap(uploadResponse.get("data"));
            if (!uploadData.isEmpty()) {
                return uploadData;
            }
        }

        Map<String, Object> fallback = new LinkedHashMap<>(uploadConfigData);
        fallback.put("rawResponse", responseBody);
        return fallback;
    }

    private Map<String, Object> oppoSignedRequest(
            AppStoreConfig storeConfig,
            String token,
            String endpointPath,
            Map<String, Object> businessParams,
            boolean getRequest
    ) {
        if (!StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Oppo request requires clientSecret");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("access_token", token);
        payload.put("timestamp", Instant.now().getEpochSecond());
        if (businessParams != null && !businessParams.isEmpty()) {
            payload.putAll(businessParams);
        }

        String sign = signOppoPayload(payload, storeConfig.getClientSecret());
        payload.put("api_sign", sign);
        String responseBody = requestOppo(storeConfig, endpointPath, payload, getRequest);
        Map<String, Object> response = readJson(responseBody);
        ensureOppoSuccess(response, endpointPath);
        return response;
    }

    private String requestOppo(
            AppStoreConfig storeConfig,
            String endpointPath,
            Map<String, Object> payload,
            boolean getRequest
    ) {
        String url = oppoBaseUrl(endpoint(storeConfig)) + endpointPath;
        if (getRequest) {
            return executeStoreRequest(
                    trace(storeConfig, "request oppo endpoint " + endpointPath, "GET", url, payload, null),
                    () -> restClient.get()
                            .uri(url + "?" + buildQueryString(payload))
                            .retrieve()
                            .body(String.class)
            );
        }

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        payload.forEach((key, value) -> {
            if (value != null) {
                body.add(key, String.valueOf(value));
            }
        });
        return executeStoreRequest(
                trace(storeConfig, "request oppo endpoint " + endpointPath, "POST", url, null, payload),
                () -> restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(body)
                        .retrieve()
                        .body(String.class)
        );
    }

    private ReleaseStatus mapOppoStatus(Map<String, Object> appInfoData, Map<String, Object> taskStateData) {
        String auditStatusName = firstString(appInfoData, "audit_status_name", "auditStatusName");
        if (StringUtils.hasText(auditStatusName)) {
            if (auditStatusName.contains("驳回") || auditStatusName.contains("拒绝") || auditStatusName.contains("失败")) {
                return ReleaseStatus.REJECT;
            }
            if (auditStatusName.contains("通过") || auditStatusName.contains("上架") || auditStatusName.contains("上线")) {
                return ReleaseStatus.PASS;
            }
            if (auditStatusName.contains("下架")) {
                return ReleaseStatus.OFFLINE;
            }
        }

        int taskState = intValue(firstNonNull(taskStateData.get("task_state"), appInfoData.get("task_state")));
        if (taskState == -1) {
            return ReleaseStatus.REJECT;
        }
        if (taskState == 1) {
            return ReleaseStatus.AUDITING;
        }

        int auditStatus = intValue(appInfoData.get("audit_status"));
        if (auditStatus == 2) {
            return ReleaseStatus.PASS;
        }
        if (auditStatus == 3 || auditStatus == 4) {
            return ReleaseStatus.REJECT;
        }
        return ReleaseStatus.AUDITING;
    }

    private boolean isRejectStatus(Map<String, Object> appInfoData) {
        String auditStatusName = firstString(appInfoData, "audit_status_name", "auditStatusName");
        return StringUtils.hasText(auditStatusName)
                && (auditStatusName.contains("驳回") || auditStatusName.contains("拒绝"));
    }

    private void ensureOppoSuccess(Map<String, Object> response, String action) {
        int errno = intValue(response.get("errno"));
        if (errno == 0) {
            return;
        }
        Map<String, Object> data = asMap(response.get("data"));
        String message = firstNonBlank(
                firstString(data, "err_msg", "errMsg", "msg", "message"),
                firstString(response, "err_msg", "errMsg", "msg", "message"),
                "unknown error"
        );
        throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Oppo " + action + " failed: errno=" + errno + ", msg=" + message);
    }

    private String signOppoPayload(Map<String, Object> payload, String clientSecret) {
        String source = payload.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !"api_sign".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        return hmacSha256Hex(source, clientSecret);
    }

    private String oppoBaseUrl(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getBaseUrl()) ? endpoint.getBaseUrl() : OPPO_BASE_URL;
    }

    private String oppoTokenEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getTokenEndpoint()) ? endpoint.getTokenEndpoint() : OPPO_TOKEN_ENDPOINT;
    }

    private String oppoUploadConfigEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return OPPO_UPLOAD_CONFIG_ENDPOINT;
    }

    private String oppoSubmitEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getSubmitEndpoint()) ? endpoint.getSubmitEndpoint() : OPPO_SUBMIT_ENDPOINT;
    }

    private String oppoStatusEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getStatusEndpoint()) ? endpoint.getStatusEndpoint() : OPPO_STATUS_ENDPOINT;
    }

    private String oppoInfoEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return OPPO_INFO_ENDPOINT;
    }
}
