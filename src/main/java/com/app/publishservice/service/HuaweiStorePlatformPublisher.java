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
import org.springframework.http.ResponseEntity;
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
    private static final String HUAWEI_APP_LANGUAGE_INFO_ENDPOINT = "/publish/v2/app-language-info";
    private static final String HUAWEI_UPLOAD_URL_ENDPOINT = "/publish/v2/upload-url/for-obs";
    private static final String HUAWEI_APP_FILE_INFO_ENDPOINT = "/publish/v2/app-file-info";
    private static final String HUAWEI_PACKAGE_COMPILE_STATUS_ENDPOINT = "/publish/v2/package/compile/status";
    private static final String HUAWEI_APP_SUBMIT_ENDPOINT = "/publish/v2/app-submit";
    private static final DateTimeFormatter HUAWEI_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.systemDefault();
    private static final int HUAWEI_COMPILE_STATUS_SUCCESS = 0;
    private static final int HUAWEI_COMPILE_STATUS_COMPILING = 1;
    private static final int HUAWEI_COMPILE_POLL_MAX_ATTEMPTS = 6;
    private static final long HUAWEI_COMPILE_POLL_INTERVAL_MILLIS = 60_000L;
    private static final int HUAWEI_SUBMIT_COMPILING_CODE = 204144727;

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
        if (!endpoint.isMockEnabled()
                && (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret()))) {
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
                        .body(String.class),
                () -> writeJson(mockHuaweiTokenResponse())
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
        Map<String, Object> appDetailResponse = queryHuaweiAppInfo(storeConfig, token, appId, isStagedRelease(record));
        List<Path> packagePaths = resolveHuaweiPackagePaths(version);
        List<Map<String, Object>> uploadedFiles = uploadHuaweiPackages(storeConfig, token, appId, packagePaths, record);
        HuaweiFileInfoUpdateResult fileInfoUpdateResult = updateHuaweiAppFileInfo(storeConfig, token, appId, uploadedFiles, record);
        HuaweiPackageCompileStatusResult packageCompileStatusResult = waitForHuaweiPackageCompile(storeConfig, token, appId, fileInfoUpdateResult, record);
        HuaweiLanguageInfoUpdateResult languageInfoUpdateResult = updateHuaweiAppLanguageInfo(storeConfig, token, appId, version, appDetailResponse, record);
        Map<String, Object> submitResponse = submitHuaweiApp(storeConfig, token, appId, version, record);

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("appIdQuery", Map.of("packageName", appInfo.getPackageName()));
        requestLog.put("appDetail", Map.of("appId", appId, "releaseType", isStagedRelease(record) ? 3 : 1));
        requestLog.put("uploadPackages", uploadedFiles);
        requestLog.put("updateFileInfo", fileInfoUpdateResult.requestBody());
        requestLog.put("queryPackageCompileStatus", packageCompileStatusResult.requestLog());
        requestLog.put("updateLanguageInfo", languageInfoUpdateResult.requestBody());
        requestLog.put("submit", buildHuaweiSubmitRequestLog(version, record));

        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("appIdQuery", Map.of("appId", appId));
        responseLog.put("appDetail", appDetailResponse);
        responseLog.put("updateFileInfo", fileInfoUpdateResult.responseBody());
        responseLog.put("queryPackageCompileStatus", packageCompileStatusResult.responseLog());
        responseLog.put("updateLanguageInfo", languageInfoUpdateResult.responseBody());
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
                        .body(String.class),
                () -> writeJson(mockHuaweiAppIdResponse(packageName))
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
                        .body(String.class),
                () -> writeJson(mockHuaweiAppInfoResponse(appId, stagedRelease))
        );
        Map<String, Object> response = readJson(responseBody);
        ensureHuaweiSuccess(response, "query app info");
        return response;
    }

    /**
     * 解析华为包路径。
     */
    private List<Path> resolveHuaweiPackagePaths(AppVersion version) {
        return List.of(requireLocalPackage(
                firstNonBlank(version.getPackageAppUrl(), version.getPackageUrl64(), version.getPackageUrl32(), version.getPackageUrl()),
                "Huawei submit requires app_version.package_app_url or app_version.package_url_64"
        ));
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
                        .body(String.class),
                () -> writeJson(mockHuaweiUploadUrlResponse(packagePath))
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
                        .toBodilessEntity(),
                () -> ResponseEntity.ok().build()
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
                        .body(String.class),
                () -> writeJson(mockHuaweiFileInfoUpdateResponse())
        );
        Map<String, Object> response = readJson(responseBody);
        ensureHuaweiSuccess(response, "update app file info");
        return new HuaweiFileInfoUpdateResult(body, response);
    }

    private HuaweiLanguageInfoUpdateResult updateHuaweiAppLanguageInfo(
            AppStoreConfig storeConfig,
            String token,
            String appId,
            AppVersion version,
            Map<String, Object> appDetailResponse,
            AppReleaseRecord record
    ) {
        List<Map<String, Object>> body = buildHuaweiLanguageInfoBody(version, appDetailResponse);

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("appId", appId);
        queryParams.put("releaseType", isStagedRelease(record) ? 3 : 1);
        String url = huaweiBaseUrl(endpoint) + huaweiAppLanguageInfoEndpoint();
        String responseBody = executeStoreRequest(
                trace(storeConfig, "update huawei app language info", "PUT", url, queryParams, body),
                () -> restClient.put()
                        .uri(url + "?" + buildQueryString(queryParams))
                        .header("Authorization", "Bearer " + token)
                        .header("client_id", storeConfig.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockHuaweiLanguageInfoUpdateResponse())
        );
        Map<String, Object> response = readJson(responseBody);
        ensureHuaweiSuccess(response, "update app language info");
        return new HuaweiLanguageInfoUpdateResult(body, response);
    }

    private HuaweiPackageCompileStatusResult waitForHuaweiPackageCompile(
            AppStoreConfig storeConfig,
            String token,
            String appId,
            HuaweiFileInfoUpdateResult fileInfoUpdateResult,
            AppReleaseRecord record
    ) {
        List<String> pkgIds = extractHuaweiPkgIds(fileInfoUpdateResult.responseBody());
        if (pkgIds.isEmpty()) {
            throw new IllegalStateException("Huawei app file info response does not contain pkgVersion");
        }
        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("appId", appId);
        requestLog.put("pkgIds", pkgIds);
        requestLog.put("maxAttempts", HUAWEI_COMPILE_POLL_MAX_ATTEMPTS);
        requestLog.put("intervalMillis", HUAWEI_COMPILE_POLL_INTERVAL_MILLIS);

        List<Map<String, Object>> attempts = new ArrayList<>();
        for (int attempt = 1; attempt <= HUAWEI_COMPILE_POLL_MAX_ATTEMPTS; attempt++) {
            Map<String, Object> response = queryHuaweiPackageCompileStatus(storeConfig, token, appId, pkgIds);
            Map<String, Object> attemptLog = new LinkedHashMap<>();
            attemptLog.put("attempt", attempt);
            attemptLog.put("response", response);
            attempts.add(attemptLog);

            HuaweiCompileDecision decision = evaluateHuaweiCompileStatus(response);
            if (decision.success()) {
                return new HuaweiPackageCompileStatusResult(requestLog, Map.of("attempts", attempts, "finalState", "success"));
            }
            if (decision.failed()) {
                throw new IllegalStateException("Huawei package compile failed: " + decision.message());
            }
            if (attempt < HUAWEI_COMPILE_POLL_MAX_ATTEMPTS) {
                sleepHuaweiCompilePoll();
            }
        }
        throw new IllegalStateException("Huawei package compile status polling timed out after " + HUAWEI_COMPILE_POLL_MAX_ATTEMPTS + " attempts");
    }

    private Map<String, Object> queryHuaweiPackageCompileStatus(
            AppStoreConfig storeConfig,
            String token,
            String appId,
            List<String> pkgIds
    ) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("appId", appId);
        queryParams.put("pkgIds", String.join(",", pkgIds));
        String url = huaweiBaseUrl(endpoint) + huaweiPackageCompileStatusEndpoint();
        String responseBody = executeStoreRequest(
                trace(storeConfig, "query huawei package compile status", "GET", url, queryParams, null),
                () -> restClient.get()
                        .uri(url + "?" + buildQueryString(queryParams))
                        .header("Authorization", "Bearer " + token)
                        .header("client_id", storeConfig.getClientId())
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockHuaweiPackageCompileStatusResponse(pkgIds, HUAWEI_COMPILE_STATUS_SUCCESS))
        );
        Map<String, Object> response = readJson(responseBody);
        ensureHuaweiSuccess(response, "query package compile status");
        return response;
    }

    private List<String> extractHuaweiPkgIds(Map<String, Object> response) {
        List<String> pkgIds = new ArrayList<>();
        Object pkgVersionValue = response.get("pkgVersion");
        if (pkgVersionValue instanceof List<?> entries) {
            for (Object entry : entries) {
                String pkgId = stringValue(entry);
                if (StringUtils.hasText(pkgId)) {
                    pkgIds.add(pkgId);
                }
            }
        } else if (pkgVersionValue != null) {
            String pkgId = stringValue(pkgVersionValue);
            if (StringUtils.hasText(pkgId)) {
                pkgIds.add(pkgId);
            }
        }
        return pkgIds;
    }

    private HuaweiCompileDecision evaluateHuaweiCompileStatus(Map<String, Object> response) {
        Object pkgStateListValue = response.get("pkgStateList");
        if (!(pkgStateListValue instanceof List<?> pkgStates) || pkgStates.isEmpty()) {
            return new HuaweiCompileDecision(false, false, "Huawei package compile status response is missing pkgStateList");
        }
        boolean allSuccess = true;
        for (Object entry : pkgStates) {
            Map<String, Object> pkgState = asMap(entry);
            Integer successStatusValue = firstInteger(pkgState.get("successStatus"), pkgState.get("aabCompileStatus"));
            int successStatus = successStatusValue == null ? HUAWEI_COMPILE_STATUS_COMPILING : successStatusValue;
            if (successStatus == HUAWEI_COMPILE_STATUS_SUCCESS) {
                continue;
            }
            allSuccess = false;
            if (successStatus != HUAWEI_COMPILE_STATUS_COMPILING) {
                String pkgId = firstString(pkgState, "pkgId");
                String message = "pkgId=" + pkgId + ", successStatus=" + successStatus + ", failReason=" + stringValue(pkgState.get("failReason"));
                return new HuaweiCompileDecision(false, true, message);
            }
        }
        return allSuccess
                ? new HuaweiCompileDecision(true, false, null)
                : new HuaweiCompileDecision(false, false, "compiling");
    }

    private void sleepHuaweiCompilePoll() {
        try {
            Thread.sleep(HUAWEI_COMPILE_POLL_INTERVAL_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Huawei package compile status", ex);
        }
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
                        .body(String.class),
                () -> writeJson(mockHuaweiSubmitResponse())
        );
        Map<String, Object> response = readJson(responseBody);
        ensureHuaweiSubmitAccepted(response);
        return response;
    }

    /**
     * 澶勭悊鍗庝负 AppId Mock 鍝嶅簲鐩稿叧閫昏緫銆?
     */
    private Map<String, Object> mockHuaweiAppIdResponse(String packageName) {
        return Map.of(
                "ret", Map.of("code", 0, "msg", "success"),
                "appids", List.of(Map.of(
                        "value", "mock-huawei-appid",
                        "packageName", packageName
                ))
        );
    }

    private Map<String, Object> mockHuaweiTokenResponse() {
        return Map.of(
                "access_token", "mock-huawei-" + UUID.randomUUID(),
                "expires_in", 48 * 60 * 60
        );
    }

    /**
     * 澶勭悊鍗庝负 AppInfo Mock 鍝嶅簲鐩稿叧閫昏緫銆?
     */
    private Map<String, Object> mockHuaweiAppInfoResponse(String appId, boolean stagedRelease) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ret", Map.of("code", 0, "msg", "success"));
        Map<String, Object> appInfo = new LinkedHashMap<>();
        appInfo.put("appId", appId);
        appInfo.put("packageName", "com.demo.mock");
        appInfo.put("versionNumber", "1.0.0");
        appInfo.put("versionCode", "100");
        appInfo.put("releaseState", 0);
        appInfo.put("privacyPolicy", "https://mock.huawei.local/privacy");
        appInfo.put("pkgName", "mock-64.apk");
        appInfo.put("pkgSize", 1024);
        appInfo.put("icon", "https://mock.huawei.local/assets/icon.png");
        appInfo.put("featureGraphic", "https://mock.huawei.local/assets/feature-graphic.png");
        appInfo.put("banner", "https://mock.huawei.local/assets/banner.png");
        appInfo.put("videoUrl", "https://mock.huawei.local/assets/video.mp4");
        appInfo.put("showType", 1);
        appInfo.put("charge", 0);
        appInfo.put("price", 0);
        appInfo.put("website", "https://mock.huawei.local");
        appInfo.put("supportEmail", "mock@example.com");
        appInfo.put("developerName", "Mock Developer");
        appInfo.put("updateTime", "2026-03-11T09:18:00+0800");
        response.put("appInfo", appInfo);
        response.put("auditInfo", Map.of(
                "auditOpinion", "",
                "copyRightAuditOpinion", "",
                "copyRightCodeAuditOpinion", "",
                "recordAuditOpinion", ""
        ));
        response.put("languages", List.of(
                Map.of(
                        "lang", "zh-CN",
                        "appName", "Mock Demo App",
                        "appDesc", "Mock demo description zh",
                        "briefInfo", "Mock demo brief zh",
                        "newFeatures", "Mock demo new features zh",
                        "icon", "https://mock.huawei.local/assets/icon-zh.png",
                        "screenShots", "https://mock.huawei.local/assets/zh-1.png,https://mock.huawei.local/assets/zh-2.png",
                        "banner", "https://mock.huawei.local/assets/banner-zh.png",
                        "showType", 1
                ),
                Map.of(
                        "lang", "en-US",
                        "appName", "Mock Demo App",
                        "appDesc", "Mock demo description en",
                        "briefInfo", "Mock demo brief en",
                        "newFeatures", "Mock demo new features en",
                        "icon", "https://mock.huawei.local/assets/icon-en.png",
                        "screenShots", "https://mock.huawei.local/assets/en-1.png,https://mock.huawei.local/assets/en-2.png",
                        "banner", "https://mock.huawei.local/assets/banner-en.png",
                        "showType", 1
                )
        ));
        if (stagedRelease) {
            response.put("phasedReleaseInfo", Map.of(
                    "state", "RELEASE",
                    "startTime", "2026-03-11T10:00:00+0800",
                    "endTime", "2026-03-18T10:00:00+0800",
                    "percent", 30
            ));
        }
        return response;
    }

    /**
     * 澶勭悊鍗庝负 Upload URL Mock 鍝嶅簲鐩稿叧閫昏緫銆?
     */
    private Map<String, Object> mockHuaweiUploadUrlResponse(Path packagePath) {
        return Map.of(
                "ret", Map.of("code", 0, "msg", "success"),
                "urlInfo", Map.of(
                        "objectId", packagePath.getFileName().toString() + ".object",
                        "url", "https://mock.huawei.local/upload/" + packagePath.getFileName(),
                        "method", "PUT",
                        "headers", Map.of("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE)
                )
        );
    }

    /**
     * 澶勭悊鍗庝负 FileInfo Mock 鍝嶅簲鐩稿叧閫昏緫銆?
     */
    private Map<String, Object> mockHuaweiFileInfoUpdateResponse() {
        return Map.of(
                "ret", Map.of("code", 0, "msg", "success"),
                "pkgVersion", List.of("mock-pkg-version")
        );
    }

    private Map<String, Object> mockHuaweiPackageCompileStatusResponse(List<String> pkgIds, int successStatus) {
        List<Map<String, Object>> pkgStateList = new ArrayList<>();
        for (String pkgId : pkgIds) {
            pkgStateList.add(Map.of(
                    "pkgId", pkgId,
                    "aabCompileStatus", successStatus,
                    "successStatus", successStatus
            ));
        }
        return Map.of(
                "ret", Map.of("code", 0, "msg", "success"),
                "pkgStateList", pkgStateList
        );
    }

    private Map<String, Object> mockHuaweiLanguageInfoUpdateResponse() {
        return Map.of(
                "ret", Map.of("code", 0, "msg", "success")
        );
    }

    /**
     * 澶勭悊鍗庝负 Submit Mock 鍝嶅簲鐩稿叧閫昏緫銆?
     */
    private Map<String, Object> mockHuaweiSubmitResponse() {
        return Map.of("ret", Map.of("code", 0, "msg", "success"));
    }

    /**
     * 构建华为提交查询参数。
     */
    private Map<String, Object> buildHuaweiSubmitQueryParams(AppVersion version, AppReleaseRecord record, String appId) {
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("appId", appId);
        queryParams.put("releaseType", isStagedRelease(record) ? 3 : 1);

        Map<String, Object> metadata = resolveProjectMetadataContext(
                firstNonBlank(version.getPackageAppUrl(), version.getPackageUrl64(), version.getPackageUrl32(), version.getPackageUrl())
        ).metadata();
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

    private void ensureHuaweiSubmitAccepted(Map<String, Object> response) {
        Map<String, Object> ret = asMap(response.get("ret"));
        int code = intValue(ret.get("code"));
        if (code == 0 || code == HUAWEI_SUBMIT_COMPILING_CODE) {
            return;
        }
        String message = firstNonBlank(firstString(ret, "msg", "message"), firstString(response, "ret"), "unknown error");
        throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Huawei submit app failed: code=" + code + ", msg=" + message);
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

    private String huaweiPackageCompileStatusEndpoint() {
        return HUAWEI_PACKAGE_COMPILE_STATUS_ENDPOINT;
    }

    private String huaweiAppLanguageInfoEndpoint() {
        return HUAWEI_APP_LANGUAGE_INFO_ENDPOINT;
    }

    /**
     * 处理华为应用提交接口地址相关逻辑。
     */
    private String huaweiAppSubmitEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getSubmitEndpoint()) ? endpoint.getSubmitEndpoint() : HUAWEI_APP_SUBMIT_ENDPOINT;
    }

    private List<Map<String, Object>> buildHuaweiLanguageInfoBody(AppVersion version, Map<String, Object> appDetailResponse) {
        AppInfo appInfo = version == null ? null : version.getAppInfo();
        String appDescription = appInfo == null ? null : appInfo.getAppDescription();
        List<Map<String, Object>> languages = new ArrayList<>();
        Object languagesValue = appDetailResponse.get("languages");
        if (languagesValue instanceof List<?> entries) {
            for (Object entry : entries) {
                Map<String, Object> language = new LinkedHashMap<>(asMap(entry));
                language.put("newFeatures", firstNonBlank(appDescription, firstString(language, "newFeatures")));
                languages.add(language);
            }
        }
        return languages;
    }

    private record HuaweiFileInfoUpdateResult(
            Map<String, Object> requestBody,
            Map<String, Object> responseBody
    ) {
    }

    private record HuaweiLanguageInfoUpdateResult(
            List<Map<String, Object>> requestBody,
            Map<String, Object> responseBody
    ) {
    }

    private record HuaweiPackageCompileStatusResult(
            Map<String, Object> requestLog,
            Map<String, Object> responseLog
    ) {
    }

    private record HuaweiCompileDecision(
            boolean success,
            boolean failed,
            String message
    ) {
    }
}
