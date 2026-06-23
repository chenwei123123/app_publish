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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class OppoStorePlatformPublisher extends AbstractStorePlatformPublisher implements StorePlatformPublisher {

    private static final String OPPO_BASE_URL = "https://oop-openapi-cn.heytapmobi.com";
    private static final String OPPO_TOKEN_ENDPOINT = "/developer/v1/token";
    private static final String OPPO_UPLOAD_CONFIG_ENDPOINT = "/resource/v1/upload/get-upload-url";
    private static final String OPPO_MULTI_INFO_ENDPOINT = "/resource/v1/app/multi-info";
    private static final String OPPO_SUBMIT_ENDPOINT = "/resource/v1/app/upd";
    private static final String OPPO_STATUS_ENDPOINT = "/resource/v1/app/task-state";
    private static final long OPPO_TOKEN_EXPIRE_SECONDS = 48 * 60 * 60L;

    /**
     * 初始化OppoStorePlatformPublisher。
     */
    OppoStorePlatformPublisher(
            RestClient restClient,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            StoreRequestLogService storeRequestLogService
    ) {
        super(restClient, objectMapper, appProperties, storeRequestLogService);
    }

    /**
     * 判断是否支持相关数据。
     */
    @Override
    public boolean supports(AppStoreConfig storeConfig) {
        return storeConfig != null
                && storeConfig.getStoreType() != null
                && "oppo".equalsIgnoreCase(storeConfig.getStoreType().getCode());
    }

    /**
     * 刷新令牌。
     */
    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
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
                        .body(String.class),
                () -> writeJson(mockOppoTokenResponse())
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

    /**
     * 提交发布。
     */
    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return submitOppoRelease(storeConfig, version, token);
        }
    }

    /**
     * 查询审核。
     */
    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return queryOppoReview(storeConfig, record, token);
        }
    }

    /**
     * 提交OPPO 发布。
     */
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
        Map<String, Object> multiInfoResponse = oppoSignedRequest(
                storeConfig,
                token,
                oppoMultiInfoEndpoint(endpoint),
                Map.of("pkg_name", appInfo.getPackageName()),
                true
        );
        Map<String, Object> multiInfoData = asMap(multiInfoResponse.get("data"));
        Map<String, Object> currentVersionInfo = resolveOppoVersionInfo(multiInfoData, version.getVersionCode());
        submitPayload.putAll(buildOppoSubmitPayload(appInfo, version, multiInfoData, currentVersionInfo));

        List<OppoApkBundle> packageBundles = resolveOppoPackageBundles(version);
        List<Map<String, Object>> apkInfos = new ArrayList<>();
        List<Map<String, Object>> uploadConfigResponses = new ArrayList<>();
        List<Map<String, Object>> uploadResults = new ArrayList<>();
        for (OppoApkBundle packageBundle : packageBundles) {
            Map<String, Object> uploadConfigResponse = oppoSignedRequest(storeConfig, token, oppoUploadConfigEndpoint(endpoint), Map.of(), true);
            Map<String, Object> uploadConfigData = asMap(uploadConfigResponse.get("data"));
            Map<String, Object> uploadResult = uploadOppoPackage(storeConfig, uploadConfigData, packageBundle.packagePath());
            String apkUrl = firstString(uploadResult, "apk_url", "apkUrl", "file_url", "fileUrl", "url");
            if (!StringUtils.hasText(apkUrl)) {
                throw new IllegalStateException("Oppo upload succeeded but returned apk_url is missing");
            }
            String apkMd5 = firstString(uploadResult, "md5", "Md5", "file_md5", "fileMd5");
            if (!StringUtils.hasText(apkMd5)) {
                throw new IllegalStateException("Oppo upload succeeded but returned md5 is missing");
            }
            Map<String, Object> apkInfo = new LinkedHashMap<>();
            apkInfo.put("url", apkUrl);
            apkInfo.put("md5", apkMd5);
            apkInfo.put("cpu_code", packageBundle.cpuCode());
            apkInfos.add(apkInfo);
            uploadConfigResponses.add(uploadConfigResponse);
            uploadResults.add(uploadResult);
        }

        submitPayload.put("pkg_name", appInfo.getPackageName());
        submitPayload.put("version_code", version.getVersionCode());
        submitPayload.put("apk_url", writeJson(apkInfos));
        Map<String, Object> submitResponse = oppoSignedRequest(storeConfig, token, oppoSubmitEndpoint(endpoint), submitPayload, false);
        Map<String, Object> submitData = asMap(submitResponse.get("data"));
        String storeReleaseId = firstNonBlank(
                firstString(submitData, "task_id", "taskId", "id"),
                appInfo.getPackageName() + ":" + version.getVersionCode()
        );

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("multiInfo", multiInfoData);
        requestLog.put("apkInfo", apkInfos);
        requestLog.put("submit", submitPayload);
        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("multiInfo", multiInfoResponse);
        responseLog.put("uploadConfigs", uploadConfigResponses);
        responseLog.put("uploads", uploadResults);
        responseLog.put("submit", submitResponse);
        return new StoreSubmitResult(storeReleaseId, writeJson(requestLog), writeJson(responseLog), "submit success");
    }

    /**
     * 查询OPPO 审核。
     */
    private StoreReviewResult queryOppoReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        Map<String, Object> queryParams = buildOppoReviewQuery(record);
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        Map<String, Object> taskStateResponse = oppoSignedRequest(storeConfig, token, oppoStatusEndpoint(endpoint), queryParams, false);
        Map<String, Object> taskStateData = asMap(taskStateResponse.get("data"));
        ReleaseStatus releaseStatus = mapOppoStatus(taskStateData);
        String rejectReason = firstNonBlank(
                firstString(taskStateData, "err_msg", "errMsg", "msg", "message"),
                releaseStatus == ReleaseStatus.REJECT ? "Oppo review rejected" : ""
        );

        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("taskState", taskStateResponse);
        return new StoreReviewResult(releaseStatus, writeJson(responseLog), StringUtils.hasText(rejectReason) ? rejectReason : null);
    }

    /**
     * 构建OPPO 审核查询。
     */
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

    /**
     * 构建OPPO 提交载荷。
     */
    private Map<String, Object> buildOppoSubmitPayload(
            AppInfo appInfo,
            AppVersion version,
            Map<String, Object> multiInfoData,
            Map<String, Object> currentVersionInfo
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pkg_name", appInfo.getPackageName());
        //payload.put("version_code", version.getVersionCode());
        copyOppoSubmitField(payload, "version_code", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "app_name", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "second_category_id", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "third_category_id", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "summary", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "detail_desc", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "update_desc", currentVersionInfo, multiInfoData, appInfo.getAppDescription());
        copyOppoSubmitField(payload, "privacy_source_url", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "icon_url", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "pic_url", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "online_type", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "test_desc", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "copyright_url", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "business_username", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "business_email", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "business_mobile", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "age_level", currentVersionInfo, multiInfoData);
        copyOppoSubmitField(payload, "adaptive_equipment", currentVersionInfo, multiInfoData);
        return payload;
    }

    /**
     * 解析OPPO 版本 Info。
     */
    private Map<String, Object> resolveOppoVersionInfo(Map<String, Object> multiInfoData, String targetVersionCode) {
        Map<String, Object> apkInfo = asMap(multiInfoData.get("apk_info"));
        if (apkInfo.isEmpty()) {
            return Map.of();
        }
        if (StringUtils.hasText(targetVersionCode)) {
            Map<String, Object> matched = asMap(apkInfo.get(targetVersionCode));
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        for (Object value : apkInfo.values()) {
            Map<String, Object> matched = asMap(value);
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        return Map.of();
    }

    /**
     * 处理copy OPPO 提交 Field相关逻辑。
     */
    private void copyOppoSubmitField(Map<String, Object> payload, String fieldName, Map<String, Object> primary, Map<String, Object> secondary, Object... fallbacks) {
        Object value = firstNonNull(primary.get(fieldName), secondary.get(fieldName));
        if (value == null && fallbacks != null) {
            value = firstNonNull(fallbacks);
        }
        Object normalizedValue = normalizeOppoSubmitFieldValue(fieldName, value);
        if (normalizedValue == null) {
            return;
        }
        payload.put(fieldName, normalizedValue);
    }

    /**
     * 规范化OPPO 提交 Field 值。
     */
    private Object normalizeOppoSubmitFieldValue(String fieldName, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            String normalized = stringValue.trim();
            if (!StringUtils.hasText(normalized)) {
                return null;
            }
            return normalized;
        }
        if ("cover_url".equals(fieldName) || "video_url_material".equals(fieldName) || "customer_contact".equals(fieldName)) {
            return writeJson(value);
        }
        return value;
    }

    /**
     * 解析OPPO 包 Bundles。
     */
    private List<OppoApkBundle> resolveOppoPackageBundles(AppVersion version) {
        String packageUrl32 = version.getPackageUrl32();
        String packageUrl64 = version.getPackageUrl64();
        if (StringUtils.hasText(packageUrl32) && StringUtils.hasText(packageUrl64)) {
            Path packagePath32 = requireLocalPackage(packageUrl32, "Oppo 32-bit package path is empty");
            Path packagePath64 = requireLocalPackage(packageUrl64, "Oppo 64-bit package path is empty");
            if (packagePath32.equals(packagePath64)) {
                return List.of(new OppoApkBundle(packagePath32, 0));
            }
            return List.of(
                    new OppoApkBundle(packagePath32, 32),
                    new OppoApkBundle(packagePath64, 64)
            );
        }

        String singlePackageLocation = firstNonBlank(version.getPackageUrl(), packageUrl64, packageUrl32);
        return List.of(new OppoApkBundle(requireLocalPackage(singlePackageLocation, "Oppo package path is empty"), 0));
    }

    /**
     * 上传OPPO 包。
     */
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
                            .body(String.class),
                    () -> writeJson(mockOppoUploadResponse(packagePath))
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
                            .body(String.class),
                    () -> writeJson(mockOppoUploadResponse(packagePath))
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

    /**
     * 处理OPPO Signed 请求相关逻辑。
     */
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

    /**
     * 处理请求 OPPO相关逻辑。
     */
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
                            .body(String.class),
                    () -> writeJson(mockOppoEndpointResponse(endpointPath, payload))
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
                        .body(String.class),
                () -> writeJson(mockOppoEndpointResponse(endpointPath, payload))
        );
    }

    /**
     * 映射OPPO 状态。
     */
    private Map<String, Object> mockOppoTokenResponse() {
        return Map.of(
                "errno", 0,
                "data", Map.of(
                        "access_token", "mock-oppo-" + UUID.randomUUID(),
                        "expire_in", OPPO_TOKEN_EXPIRE_SECONDS
                )
        );
    }

    private Map<String, Object> mockOppoEndpointResponse(String endpointPath, Map<String, Object> payload) {
        if (OPPO_MULTI_INFO_ENDPOINT.equals(endpointPath)) {
            String packageName = firstString(payload, "pkg_name");
            Map<String, Object> versionInfo = new LinkedHashMap<>();
            versionInfo.put("version_code", "100");
            versionInfo.put("app_name", firstNonBlank(packageName, "mock-oppo-app"));
            versionInfo.put("update_desc", "mock oppo update");
            versionInfo.put("privacy_source_url", "https://mock.oppo.local/privacy");
            versionInfo.put("online_type", 1);
            versionInfo.put("adaptive_equipment", 0);
            return Map.of(
                    "errno", 0,
                    "data", Map.of(
                            "pkg_name", packageName,
                            "apk_info", Map.of("100", versionInfo)
                    )
            );
        }
        if (OPPO_UPLOAD_CONFIG_ENDPOINT.equals(endpointPath)) {
            return Map.of(
                    "errno", 0,
                    "data", Map.of(
                            "upload_url", "https://mock.oppo.local/upload",
                            "policy", "mock-policy",
                            "signature", "mock-signature"
                    )
            );
        }
        if (OPPO_SUBMIT_ENDPOINT.equals(endpointPath)) {
            return Map.of(
                    "errno", 0,
                    "data", Map.of("task_id", "oppo-" + UUID.randomUUID())
            );
        }
        if (OPPO_STATUS_ENDPOINT.equals(endpointPath)) {
            return Map.of(
                    "errno", 0,
                    "data", Map.of("task_state", 2)
            );
        }
        throw new IllegalStateException("Unsupported Oppo mock endpoint: " + endpointPath);
    }

    private Map<String, Object> mockOppoUploadResponse(Path packagePath) {
        return Map.of(
                "errno", 0,
                "data", Map.of(
                        "apk_url", "https://mock.oppo.local/package/" + packagePath.getFileName(),
                        "md5", "mock-md5-" + packagePath.getFileName(),
                        "file_name", packagePath.getFileName().toString()
                )
        );
    }

    private ReleaseStatus mapOppoStatus(Map<String, Object> taskStateData) {
        int taskState = intValue(taskStateData.get("task_state"));
        if (taskState == -1) {
            return ReleaseStatus.REJECT;
        }
        if (taskState == 1) {
            return ReleaseStatus.AUDITING;
        }
        if (taskState == 2) {
            return ReleaseStatus.PASS;
        }
        if (taskState == 3) {
            return ReleaseStatus.OFFLINE;
        }
        return ReleaseStatus.AUDITING;
    }

    /**
     * 确保OPPO Success。
     */
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

    /**
     * 签名OPPO 载荷。
     */
    private String signOppoPayload(Map<String, Object> payload, String clientSecret) {
        String source = payload.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !"api_sign".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        return hmacSha256Hex(source, clientSecret);
    }

    /**
     * 处理OPPO Base URL相关逻辑。
     */
    private String oppoBaseUrl(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getBaseUrl()) ? endpoint.getBaseUrl() : OPPO_BASE_URL;
    }

    /**
     * 处理OPPO 令牌接口地址相关逻辑。
     */
    private String oppoTokenEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getTokenEndpoint()) ? endpoint.getTokenEndpoint() : OPPO_TOKEN_ENDPOINT;
    }

    /**
     * 处理OPPO 上传配置接口地址相关逻辑。
     */
    private String oppoUploadConfigEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return OPPO_UPLOAD_CONFIG_ENDPOINT;
    }

    /**
     * 处理OPPO Multi Info 接口地址相关逻辑。
     */
    private String oppoMultiInfoEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return OPPO_MULTI_INFO_ENDPOINT;
    }

    /**
     * 处理OPPO 提交接口地址相关逻辑。
     */
    private String oppoSubmitEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getSubmitEndpoint()) ? endpoint.getSubmitEndpoint() : OPPO_SUBMIT_ENDPOINT;
    }

    /**
     * 处理OPPO 状态接口地址相关逻辑。
     */
    private String oppoStatusEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getStatusEndpoint()) ? endpoint.getStatusEndpoint() : OPPO_STATUS_ENDPOINT;
    }

    private record OppoApkBundle(
            Path packagePath,
            int cpuCode
    ) {
    }
}
