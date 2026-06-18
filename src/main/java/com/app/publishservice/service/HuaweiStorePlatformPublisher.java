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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class HuaweiStorePlatformPublisher extends AbstractStorePlatformPublisher implements StorePlatformPublisher {

    private static final String HUAWEI_BASE_URL = "https://connect-api.cloud.huawei.com/api";
    private static final String HUAWEI_TOKEN_ENDPOINT = "/oauth2/v1/token";
    private static final String HUAWEI_APPID_LIST_ENDPOINT = "/publish/v2/appid-list";
    private static final String HUAWEI_APP_INFO_ENDPOINT = "/publish/v2/app-info";
    private static final String HUAWEI_UPLOAD_URL_ENDPOINT = "/publish/v2/upload-url/for-obs";
    private static final String HUAWEI_APP_FILE_INFO_ENDPOINT = "/publish/v2/app-file-info";
    private static final String HUAWEI_APP_SUBMIT_ENDPOINT = "/publish/v2/app-submit";
    private static final DateTimeFormatter HUAWEI_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.systemDefault();

    /**
     * 初始化HuaweiStorePlatformPublisher。
     */
    HuaweiStorePlatformPublisher(
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
                && "huawei".equalsIgnoreCase(storeConfig.getStoreType().getCode());
    }

    /**
     * 刷新令牌。
     */
    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            log.info("Use mock huawei token refresh, storeConfigId={}", storeConfig.getId());
            return new TokenPayload(
                    TokenType.ACCESS_TOKEN.getCode(),
                    "mock-huawei-" + UUID.randomUUID(),
                    LocalDateTime.now().plusHours(48)
            );
        }
        if (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Huawei token refresh requires clientId and clientSecret");
        }

        String tokenUrl = huaweiBaseUrl(endpoint) + huaweiTokenEndpoint(endpoint);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("client_id", storeConfig.getClientId());
        body.put("client_secret", storeConfig.getClientSecret());

        String responseBody = executeStoreRequest(
                trace(storeConfig, "refresh huawei token", "POST", tokenUrl, null, body),
                () -> restClient.post()
                        .uri(tokenUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        String token = firstString(response, "access_token", "accessToken", "token");
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("Huawei token response does not contain access_token");
        }
        long expiresIn = longValue(firstNonNull(response.get("expires_in"), response.get("expiresIn")), 48 * 60 * 60L);
        return new TokenPayload(TokenType.ACCESS_TOKEN.getCode(), token, LocalDateTime.now().plusSeconds(expiresIn));
    }

    /**
     * 提交发布。
     */
    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return submitHuaweiRelease(storeConfig, version, record, token);
        }
    }

    /**
     * 查询审核。
     */
    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return queryHuaweiReview(storeConfig, record, token);
        }
    }

    /**
     * 提交华为发布。
     */
    private StoreSubmitResult submitHuaweiRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        AppInfo appInfo = version.getAppInfo();
        if (appInfo == null || !StringUtils.hasText(appInfo.getPackageName())) {
            throw new IllegalArgumentException("Huawei submit requires app packageName");
        }
        if (!StringUtils.hasText(storeConfig.getClientId())) {
            throw new IllegalArgumentException("Huawei submit requires clientId");
        }

        String appId = queryHuaweiAppId(storeConfig, token, appInfo.getPackageName());
        List<Path> packagePaths = resolveHuaweiPackagePaths(version);
        List<Map<String, Object>> uploadedFiles = uploadHuaweiPackages(storeConfig, token, appId, packagePaths, record);
        HuaweiFileInfoUpdateResult fileInfoUpdateResult = updateHuaweiAppFileInfo(storeConfig, token, appId, uploadedFiles, record);
        Map<String, Object> submitResponse = submitHuaweiApp(storeConfig, token, appId, version, record);

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("appIdQuery", Map.of("packageName", appInfo.getPackageName()));
        requestLog.put("uploadPackages", uploadedFiles);
        requestLog.put("updateFileInfo", fileInfoUpdateResult.requestBody());
        requestLog.put("submit", buildHuaweiSubmitRequestLog(version, record));

        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("appIdQuery", Map.of("appId", appId));
        responseLog.put("updateFileInfo", fileInfoUpdateResult.responseBody());
        responseLog.put("submit", submitResponse);

        String storeReleaseId = appId;
        String message = huaweiRetMessage(submitResponse);
        return new StoreSubmitResult(storeReleaseId, writeJson(requestLog), writeJson(responseLog), StringUtils.hasText(message) ? message : "submit success");
    }

    /**
     * 查询华为审核。
     */
    private StoreReviewResult queryHuaweiReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        String appId = queryHuaweiAppId(storeConfig, token, record.getPackageName());
        Map<String, Object> response = queryHuaweiAppInfo(storeConfig, token, appId, isStagedRelease(record));
        Map<String, Object> appInfo = asMap(response.get("appInfo"));
        Map<String, Object> auditInfo = asMap(response.get("auditInfo"));
        Map<String, Object> phasedReleaseInfo = asMap(response.get("phasedReleaseInfo"));

        String rejectReason = firstNonBlank(
                firstString(auditInfo, "auditOpinion"),
                firstString(auditInfo, "copyRightAuditOpinion"),
                firstString(auditInfo, "copyRightCodeAuditOpinion"),
                firstString(auditInfo, "recordAuditOpinion")
        );
        ReleaseStatus releaseStatus = isStagedRelease(record)
                ? mapHuaweiStagedStatus(appInfo, phasedReleaseInfo, rejectReason)
                : mapHuaweiReleaseStatus(appInfo, rejectReason);
        return new StoreReviewResult(releaseStatus, writeJson(response), StringUtils.hasText(rejectReason) ? rejectReason : null);
    }

    /**
     * 查询华为应用 Id。
     */
    private String queryHuaweiAppId(AppStoreConfig storeConfig, String token, String packageName) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            return "mock-huawei-appid";
        }

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("packageName", packageName);
        String url = huaweiBaseUrl(endpoint) + huaweiAppIdListEndpoint();
        String responseBody = executeStoreRequest(
                trace(storeConfig, "query huawei app id", "GET", url, queryParams, Map.of("client_id", storeConfig.getClientId())),
                () -> restClient.get()
                        .uri(url + "?" + buildQueryString(queryParams))
                        .header("Authorization", "Bearer " + token)
                        .header("client_id", storeConfig.getClientId())
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        ensureHuaweiSuccess(response, "query app id");

        Object appidsValue = response.get("appids");
        if (appidsValue instanceof List<?> appids) {
            for (Object item : appids) {
                Map<String, Object> appIdEntry = asMap(item);
                String appId = firstString(appIdEntry, "value", "appId", "id");
                if (StringUtils.hasText(appId)) {
                    return appId;
                }
            }
        }
        throw new IllegalStateException("Huawei appId not found for packageName: " + packageName);
    }

    /**
     * 查询华为应用 Info。
     */
    private Map<String, Object> queryHuaweiAppInfo(AppStoreConfig storeConfig, String token, String appId, boolean stagedRelease) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            Map<String, Object> mockResponse = new LinkedHashMap<>();
            mockResponse.put("ret", Map.of("code", 0, "msg", "success"));
            mockResponse.put("appInfo", Map.of("releaseState", stagedRelease ? 0 : 4));
            if (stagedRelease) {
                mockResponse.put("phasedReleaseInfo", Map.of("state", "RELEASE"));
            }
            return mockResponse;
        }

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("appId", appId);
        queryParams.put("releaseType", stagedRelease ? 3 : 1);
        String url = huaweiBaseUrl(endpoint) + huaweiAppInfoEndpoint(endpoint);
        String responseBody = executeStoreRequest(
                trace(storeConfig, "query huawei app info", "GET", url, queryParams, Map.of("client_id", storeConfig.getClientId())),
                () -> restClient.get()
                        .uri(url + "?" + buildQueryString(queryParams))
                        .header("Authorization", "Bearer " + token)
                        .header("client_id", storeConfig.getClientId())
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        ensureHuaweiSuccess(response, "query app info");
        return response;
    }

    /**
     * 解析华为包路径。
     */
    private List<Path> resolveHuaweiPackagePaths(AppVersion version) {
        List<Path> paths = new ArrayList<>();
        if (StringUtils.hasText(version.getPackageUrl32())) {
            paths.add(requireLocalPackage(version.getPackageUrl32(), "Huawei 32-bit package path is empty"));
        }
        if (StringUtils.hasText(version.getPackageUrl64())) {
            Path highPath = requireLocalPackage(version.getPackageUrl64(), "Huawei 64-bit package path is empty");
            if (paths.stream().noneMatch(highPath::equals)) {
                paths.add(highPath);
            }
        }
        if (paths.isEmpty()) {
            paths.add(requireLocalPackage(version.getPackageUrl(), "Huawei package path is empty"));
        }
        return paths;
    }

    /**
     * 上传华为包。
     */
    private List<Map<String, Object>> uploadHuaweiPackages(
            AppStoreConfig storeConfig,
            String token,
            String appId,
            List<Path> packagePaths,
            AppReleaseRecord record
    ) {
        List<Map<String, Object>> uploadedFiles = new ArrayList<>();
        for (Path packagePath : packagePaths) {
            Map<String, Object> uploadUrlInfo = getHuaweiUploadUrl(storeConfig, token, appId, packagePath, record);
            uploadHuaweiFile(storeConfig, uploadUrlInfo, packagePath);
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("fileName", packagePath.getFileName().toString());
            fileInfo.put("fileDestUrl", firstString(uploadUrlInfo, "objectId"));
            uploadedFiles.add(fileInfo);
        }
        return uploadedFiles;
    }

    /**
     * 获取华为上传 URL。
     */
    private Map<String, Object> getHuaweiUploadUrl(
            AppStoreConfig storeConfig,
            String token,
            String appId,
            Path packagePath,
            AppReleaseRecord record
    ) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            return Map.of(
                    "objectId", packagePath.getFileName().toString() + ".object",
                    "url", "mock-upload-url",
                    "method", "PUT",
                    "headers", Map.of("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE)
            );
        }

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("appId", appId);
        queryParams.put("fileName", packagePath.getFileName().toString());
        queryParams.put("sha256", sha256Hex(packagePath));
        queryParams.put("contentLength", fileSize(packagePath));
        queryParams.put("releaseType", 1);
        Integer chineseMainlandFlag = resolveHuaweiChineseMainlandFlag(packagePath);
        if (chineseMainlandFlag != null) {
            queryParams.put("chineseMainlandFlag", chineseMainlandFlag);
        }

        String url = huaweiBaseUrl(endpoint) + huaweiUploadUrlEndpoint();
        String responseBody = executeStoreRequest(
                trace(storeConfig, "get huawei upload url", "GET", url, queryParams, Map.of("client_id", storeConfig.getClientId())),
                () -> restClient.get()
                        .uri(url + "?" + buildQueryString(queryParams))
                        .header("Authorization", "Bearer " + token)
                        .header("client_id", storeConfig.getClientId())
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        ensureHuaweiSuccess(response, "get upload url");
        Map<String, Object> urlInfo = asMap(response.get("urlInfo"));
        if (!StringUtils.hasText(firstString(urlInfo, "url")) || !StringUtils.hasText(firstString(urlInfo, "objectId"))) {
            throw new IllegalStateException("Huawei upload url response is missing urlInfo.url or urlInfo.objectId");
        }
        return urlInfo;
    }

    /**
     * 上传华为文件。
     */
    private void uploadHuaweiFile(AppStoreConfig storeConfig, Map<String, Object> uploadUrlInfo, Path packagePath) {
        if (endpoint(storeConfig).isMockEnabled()) {
            return;
        }
        String url = firstString(uploadUrlInfo, "url");
        @SuppressWarnings("unchecked")
        Map<String, Object> headerMap = uploadUrlInfo.get("headers") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        HttpHeaders headers = new HttpHeaders();
        headerMap.forEach((key, value) -> {
            if (value != null && !"host".equalsIgnoreCase(key)) {
                headers.add(key, String.valueOf(value));
            }
        });
        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }

        executeStoreRequest(
                trace(
                        storeConfig,
                        "upload huawei package",
                        "PUT",
                        url,
                        null,
                        requestPayload(Map.of("fileName", packagePath.getFileName().toString(), "objectId", firstString(uploadUrlInfo, "objectId")), headerMap)
                ),
                () -> restClient.put()
                        .uri(url)
                        .headers(httpHeaders -> httpHeaders.addAll(headers))
                        .body(new FileSystemResource(packagePath))
                        .retrieve()
                        .toBodilessEntity()
        );
    }

    /**
     * 更新华为应用文件 Info。
     */
    private HuaweiFileInfoUpdateResult updateHuaweiAppFileInfo(
            AppStoreConfig storeConfig,
            String token,
            String appId,
            List<Map<String, Object>> uploadedFiles,
            AppReleaseRecord record
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileType", 5);
        body.put("files", uploadedFiles);

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            return new HuaweiFileInfoUpdateResult(body, Map.of("ret", Map.of("code", 0, "msg", "success"), "pkgVersion", List.of("mock-pkg-version")));
        }

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("appId", appId);
        queryParams.put("releaseType", isStagedRelease(record) ? 3 : 1);
        String url = huaweiBaseUrl(endpoint) + huaweiAppFileInfoEndpoint();
        String responseBody = executeStoreRequest(
                trace(storeConfig, "update huawei app file info", "PUT", url, queryParams, body),
                () -> restClient.put()
                        .uri(url + "?" + buildQueryString(queryParams))
                        .header("Authorization", "Bearer " + token)
                        .header("client_id", storeConfig.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        ensureHuaweiSuccess(response, "update app file info");
        return new HuaweiFileInfoUpdateResult(body, response);
    }

    /**
     * 提交华为应用。
     */
    private Map<String, Object> submitHuaweiApp(
            AppStoreConfig storeConfig,
            String token,
            String appId,
            AppVersion version,
            AppReleaseRecord record
    ) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        Map<String, Object> queryParams = buildHuaweiSubmitQueryParams(version, record, appId);
        Map<String, Object> body = buildHuaweiSubmitBody(version, record);

        if (endpoint.isMockEnabled()) {
            return Map.of("ret", Map.of("code", 0, "msg", "success"));
        }

        String url = huaweiBaseUrl(endpoint) + huaweiAppSubmitEndpoint(endpoint);
        String responseBody = executeStoreRequest(
                trace(storeConfig, "submit huawei app", "POST", url, queryParams, body),
                () -> restClient.post()
                        .uri(url + "?" + buildQueryString(queryParams))
                        .header("Authorization", "Bearer " + token)
                        .header("client_id", storeConfig.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        ensureHuaweiSuccess(response, "submit app");
        return response;
    }

    /**
     * 构建华为提交查询参数。
     */
    private Map<String, Object> buildHuaweiSubmitQueryParams(AppVersion version, AppReleaseRecord record, String appId) {
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("appId", appId);
        queryParams.put("releaseType", isStagedRelease(record) ? 3 : 1);

        Map<String, Object> metadata = resolveProjectMetadataContext(firstNonBlank(version.getPackageUrl64(), version.getPackageUrl32(), version.getPackageUrl())).metadata();
        String releaseTime = firstNonBlank(
                stringValue(metadataLookup(metadata, "huawei", "releaseTime")),
                stringValue(metadataLookup(metadata, null, "huaweiReleaseTime"))
        );
        if (StringUtils.hasText(releaseTime)) {
            queryParams.put("releaseTime", releaseTime);
        }

        AppInfo appInfo = version.getAppInfo();
        String remark = firstNonBlank(
                stringValue(metadataLookup(metadata, "huawei", "remark")),
                normalizeStageText(10, 300,  appInfo == null ? null : appInfo.getAppDescription(), appInfo == null ? null : appInfo.getAppName(), appInfo == null ? null : appInfo.getPackageName(), "Huawei submit remark")
        );
        if (StringUtils.hasText(remark)) {
            queryParams.put("remark", remark);
        }
        return queryParams;
    }

    /**
     * 构建华为提交报文。
     */
    private Map<String, Object> buildHuaweiSubmitBody(AppVersion version, AppReleaseRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!isStagedRelease(record)) {
            return body;
        }
        AppInfo appInfo = version.getAppInfo();
        body.put("phasedReleaseStartTime", formatHuaweiDateTime(record.getGrayStartTime()));
        body.put("phasedReleaseEndTime", formatHuaweiDateTime(record.getGrayEndTime()));
        body.put("phasedReleasePercent", formatHuaweiPercent(record.getGrayPercent()));
        body.put(
                "phasedReleaseDescription",
                normalizeStageText(
                        10,
                        500,
                        appInfo == null ? null : appInfo.getAppDescription(),
                        appInfo == null ? null : appInfo.getAppName(),
                        appInfo == null ? null : appInfo.getPackageName(),
                        "Huawei phased release description"
                )
        );
        return body;
    }

    /**
     * 构建华为提交请求日志。
     */
    private Map<String, Object> buildHuaweiSubmitRequestLog(AppVersion version, AppReleaseRecord record) {
        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("query", buildHuaweiSubmitQueryParams(version, record, "resolved-at-runtime"));
        requestLog.put("body", buildHuaweiSubmitBody(version, record));
        return requestLog;
    }

    /**
     * 解析华为 Chinese Mainland Flag。
     */
    private Integer resolveHuaweiChineseMainlandFlag(Path packagePath) {
        Map<String, Object> metadata = resolveProjectMetadataContext(packagePath.toString()).metadata();
        return firstInteger(
                metadataLookup(metadata, "huawei", "chineseMainlandFlag"),
                metadataLookup(metadata, null, "huaweiChineseMainlandFlag")
        );
    }

    /**
     * 处理字符串值相关逻辑。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 映射华为发布状态。
     */
    private ReleaseStatus mapHuaweiReleaseStatus(Map<String, Object> appInfoData, String rejectReason) {
        int releaseState = intValue(appInfoData.get("releaseState"));
        return switch (releaseState) {
            case 0 -> ReleaseStatus.PASS;
            case 1, 8, 9, 13 -> ReleaseStatus.REJECT;
            case 2, 10, 11 -> ReleaseStatus.OFFLINE;
            default -> StringUtils.hasText(rejectReason) ? ReleaseStatus.REJECT : ReleaseStatus.AUDITING;
        };
    }

    /**
     * 映射华为灰度状态。
     */
    private ReleaseStatus mapHuaweiStagedStatus(Map<String, Object> appInfoData, Map<String, Object> phasedReleaseInfo, String rejectReason) {
        ReleaseStatus baseStatus = mapHuaweiReleaseStatus(appInfoData, rejectReason);
        if (baseStatus == ReleaseStatus.REJECT || baseStatus == ReleaseStatus.OFFLINE) {
            return baseStatus;
        }
        String phaseState = firstString(phasedReleaseInfo, "state");
        if (!StringUtils.hasText(phaseState)) {
            return baseStatus;
        }
        return switch (phaseState.toUpperCase(Locale.ROOT)) {
            case "RELEASE" -> ReleaseStatus.PASS;
            case "SUSPEND", "CANCEL" -> ReleaseStatus.OFFLINE;
            default -> baseStatus;
        };
    }

    /**
     * 处理华为 Ret 消息相关逻辑。
     */
    private String huaweiRetMessage(Map<String, Object> response) {
        return firstString(asMap(response.get("ret")), "msg", "message");
    }

    /**
     * 确保华为 Success。
     */
    private void ensureHuaweiSuccess(Map<String, Object> response, String action) {
        Map<String, Object> ret = asMap(response.get("ret"));
        int code = intValue(ret.get("code"));
        if (code == 0) {
            return;
        }
        String message = firstNonBlank(firstString(ret, "msg", "message"), firstString(response, "ret"), "unknown error");
        throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Huawei " + action + " failed: code=" + code + ", msg=" + message);
    }

    /**
     * 格式化华为 Date 时间。
     */
    private String formatHuaweiDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(DEFAULT_ZONE_ID).format(HUAWEI_DATE_TIME_FORMATTER);
    }

    /**
     * 格式化华为 Percent。
     */
    private String formatHuaweiPercent(Number percent) {
        double value = percent == null ? 0D : percent.doubleValue();
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * 处理华为 Base URL相关逻辑。
     */
    private String huaweiBaseUrl(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getBaseUrl()) ? endpoint.getBaseUrl() : HUAWEI_BASE_URL;
    }

    /**
     * 处理华为令牌接口地址相关逻辑。
     */
    private String huaweiTokenEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getTokenEndpoint()) ? endpoint.getTokenEndpoint() : HUAWEI_TOKEN_ENDPOINT;
    }

    /**
     * 处理华为应用 Id 列表接口地址相关逻辑。
     */
    private String huaweiAppIdListEndpoint() {
        return HUAWEI_APPID_LIST_ENDPOINT;
    }

    /**
     * 处理华为应用 Info 接口地址相关逻辑。
     */
    private String huaweiAppInfoEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getStatusEndpoint()) ? endpoint.getStatusEndpoint() : HUAWEI_APP_INFO_ENDPOINT;
    }

    /**
     * 处理华为上传 URL 接口地址相关逻辑。
     */
    private String huaweiUploadUrlEndpoint() {
        return HUAWEI_UPLOAD_URL_ENDPOINT;
    }

    /**
     * 处理华为应用文件 Info 接口地址相关逻辑。
     */
    private String huaweiAppFileInfoEndpoint() {
        return HUAWEI_APP_FILE_INFO_ENDPOINT;
    }

    /**
     * 处理华为应用提交接口地址相关逻辑。
     */
    private String huaweiAppSubmitEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getSubmitEndpoint()) ? endpoint.getSubmitEndpoint() : HUAWEI_APP_SUBMIT_ENDPOINT;
    }

    private record HuaweiFileInfoUpdateResult(
            Map<String, Object> requestBody,
            Map<String, Object> responseBody
    ) {
    }
}
