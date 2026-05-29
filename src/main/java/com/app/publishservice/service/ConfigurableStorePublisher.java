package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.domain.entity.AppInfo;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.ReleaseStatus;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.domain.enums.TokenType;
import com.app.publishservice.service.model.StoreReviewResult;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.app.publishservice.service.model.TokenPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ConfigurableStorePublisher implements StorePublisher {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableStorePublisher.class);
    private static final String VIVO_PRODUCTION_BASE_URL = "https://developer-api.vivo.com.cn/router/rest";
    private static final String VIVO_SANDBOX_BASE_URL = "https://sandbox-developer-api.vivo.com.cn/router/rest";
    private static final String VIVO_METHOD_UPLOAD_APK = "app.upload.apk.app";
    private static final String VIVO_METHOD_CREATE_UPDATE_STAGE = "app.sync.create.update.stage.app";
    private static final String VIVO_METHOD_QUERY_STAGE_DETAILS = "app.query.stage.details";
    private static final String VIVO_TARGET_APP_KEY = "developer";
    private static final String VIVO_FORMAT = "json";
    private static final String VIVO_SIGN_METHOD = "hmac";
    private static final String VIVO_PROTOCOL_VERSION = "1.0";
    private static final String OPPO_BASE_URL = "https://oop-openapi-cn.heytapmobi.com";
    private static final String OPPO_TOKEN_ENDPOINT = "/developer/v1/token";
    private static final String OPPO_UPLOAD_CONFIG_ENDPOINT = "/resource/v1/upload/get-upload-url";
    private static final String OPPO_SUBMIT_ENDPOINT = "/resource/v1/app/upd";
    private static final String OPPO_STATUS_ENDPOINT = "/resource/v1/app/task-state";
    private static final String OPPO_INFO_ENDPOINT = "/resource/v1/app/info";
    private static final long OPPO_TOKEN_EXPIRE_SECONDS = 48 * 60 * 60L;
    private static final DateTimeFormatter VIVO_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter PACKAGE_CACHE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.systemDefault();

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public ConfigurableStorePublisher(ObjectMapper objectMapper, AppProperties appProperties) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        log.debug("Start refresh token, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
        if (isVivoStore(storeConfig)) {
            String marker = StringUtils.hasText(storeConfig.getClientId()) ? storeConfig.getClientId() : "vivo-static";
            return new TokenPayload(TokenType.STATIC.getCode(), marker, LocalDateTime.now().plusYears(10));
        }

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (StringUtils.hasText(storeConfig.getToken())) {
            log.debug("Use configured static token, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
            return new TokenPayload(TokenType.STATIC.getCode(), storeConfig.getToken(), LocalDateTime.now().plusYears(10));
        }
        if (isOppoStore(storeConfig)) {
            return refreshOppoToken(storeConfig);
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
        body.put("miPublicKey", storeConfig.getMiPublicKey());
        body.put("miPrivateKey", storeConfig.getMiPrivateKey());
        body.put("ipWhitelist", storeConfig.getIpWhitelist());

        log.info("Request store token, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(endpoint.getBaseUrl() + endpoint.getTokenEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        String token = firstString(response, "accessToken", "token");
        Number expiresIn = response.get("expiresIn") instanceof Number number ? number : 3600;
        return new TokenPayload(TokenType.ACCESS_TOKEN.getCode(), token, LocalDateTime.now().plusSeconds(expiresIn.longValue()));
    }

    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        log.info(
                "Start submit release, storeType={}, versionId={}, appId={}, releaseType={}",
                storeConfig.getStoreType().getCode(),
                version.getId(),
                version.getAppId(),
                record == null ? null : record.getReleaseType()
        );
        if (isVivoStore(storeConfig)) {
            return submitVivoRelease(storeConfig, version, record);
        }
        if (isOppoStore(storeConfig)) {
            return submitOppoRelease(storeConfig, version, token);
        }

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
                    writeJson(payload),
                    "{\"accepted\":true,\"storeReleaseId\":\"" + storeReleaseId + "\"}",
                    "mock submit success"
            );
        }

        log.info("Submit release to store endpoint, storeType={}, versionId={}", storeConfig.getStoreType().getCode(), version.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(endpoint.getBaseUrl() + endpoint.getSubmitEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .body(payload)
                .retrieve()
                .body(Map.class);

        String storeReleaseId = firstString(response, "storeReleaseId", "id");
        return new StoreSubmitResult(storeReleaseId, writeJson(payload), writeJson(response), "submit success");
    }

    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        log.debug("Start query review, releaseId={}, storeType={}, releaseType={}", record.getId(), record.getStoreType(), record.getReleaseType());
        if (isVivoStore(storeConfig) && isStagedRelease(record)) {
            return queryVivoStageDetails(storeConfig, record);
        }
        if (isOppoStore(storeConfig)) {
            return queryOppoReview(storeConfig, record, token);
        }

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
        Map<String, Object> response = restClient.get()
                .uri(statusUrl)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(Map.class);

        Object statusCode = response.get("status");
        ReleaseStatus releaseStatus = mapStatus(statusCode == null ? "auditing" : statusCode.toString());
        String rejectReason = response.get("rejectReason") == null ? null : response.get("rejectReason").toString();
        return new StoreReviewResult(releaseStatus, writeJson(response), rejectReason);
    }

    private StoreApiProperties.StoreEndpointProperties endpoint(AppStoreConfig storeConfig) {
        return appProperties.getStoreApi().getStore(storeConfig.getStoreType().getCode());
    }

    private TokenPayload refreshOppoToken(AppStoreConfig storeConfig) {
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

        String responseBody = restClient.get()
                .uri(oppoBaseUrl(endpoint) + oppoTokenEndpoint(endpoint) + "?" + buildQueryString(Map.of(
                        "client_id", storeConfig.getClientId(),
                        "client_secret", storeConfig.getClientSecret()
                )))
                .retrieve()
                .body(String.class);

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

    private StoreSubmitResult submitVivoRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record) {
        if (isStagedRelease(record)) {
            return submitVivoStageRelease(storeConfig, version, record);
        }
        return submitVivoApkUpload(storeConfig, version, false);
    }

    private StoreSubmitResult submitVivoStageRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record) {
        StoreSubmitResult uploadResult = submitVivoApkUpload(storeConfig, version, true);
        Map<String, Object> payload = buildVivoStagePayload(storeConfig, version, record, uploadResult.storeReleaseId());
        String sign = signVivoPayload(payload, storeConfig.getClientSecret());
        Map<String, Object> stageRequestLog = new LinkedHashMap<>(payload);
        stageRequestLog.put("sign", sign);

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            Map<String, Object> requestLog = new LinkedHashMap<>();
            requestLog.put("apkUpload", readJson(uploadResult.requestLog()));
            requestLog.put("stageSubmit", stageRequestLog);
            Map<String, Object> responseLog = new LinkedHashMap<>();
            responseLog.put("apkUpload", readJson(uploadResult.responseLog()));
            responseLog.put("stageSubmit", Map.of("code", 0, "subCode", "0", "msg", "mock stage submit success"));
            return new StoreSubmitResult(
                    uploadResult.storeReleaseId(),
                    writeJson(requestLog),
                    writeJson(responseLog),
                    "mock stage submit success"
            );
        }

        log.info(
                "Submit vivo staged release, versionId={}, packageName={}, grayPercent={}, grayStartTime={}, grayEndTime={}",
                version.getId(),
                version.getAppInfo().getPackageName(),
                record.getGrayPercent(),
                record.getGrayStartTime(),
                record.getGrayEndTime()
        );
        String responseBody = postVivoForm(endpoint, payload, sign);
        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("apkUpload", readJson(uploadResult.requestLog()));
        requestLog.put("stageSubmit", stageRequestLog);
        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("apkUpload", readJson(uploadResult.responseLog()));
        responseLog.put("stageSubmit", response);
        String message = firstString(response, "msg", "message");
        return new StoreSubmitResult(
                uploadResult.storeReleaseId(),
                writeJson(requestLog),
                writeJson(responseLog),
                StringUtils.hasText(message) ? message : "stage submit success"
        );
    }

    private StoreSubmitResult submitVivoApkUpload(AppStoreConfig storeConfig, AppVersion version, boolean stageUpload) {
        Path packagePath = requireLocalPackage(version);
        String fileName = packagePath.getFileName().toString();
        if (!fileName.toLowerCase().endsWith(".apk")) {
            throw new IllegalArgumentException("Vivo upload only supports APK packages");
        }
        if (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Vivo upload requires clientId as access_key and clientSecret as access_secret");
        }

        String fileMd5 = md5Hex(packagePath);
        Map<String, Object> payload = buildVivoUploadPayload(storeConfig, version, fileMd5, stageUpload);
        String sign = signVivoPayload(payload, storeConfig.getClientSecret());
        Map<String, Object> requestLog = new LinkedHashMap<>(payload);
        requestLog.put("sign", sign);
        requestLog.put("fileName", fileName);

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            log.info("Use mock vivo upload, versionId={}, fileName={}, stageUpload={}", version.getId(), fileName, stageUpload);
            String storeReleaseId = "vivo-" + UUID.randomUUID();
            return new StoreSubmitResult(
                    storeReleaseId,
                    writeJson(requestLog),
                    "{\"code\":0,\"subCode\":\"0\",\"msg\":\"mock upload success\",\"data\":{\"serialnumber\":\"" + storeReleaseId + "\"}}",
                    "mock upload success"
            );
        }

        log.info(
                "Submit vivo apk upload, versionId={}, fileName={}, packageName={}, stageUpload={}",
                version.getId(),
                fileName,
                version.getAppInfo().getPackageName(),
                stageUpload
        );
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        payload.forEach((key, value) -> body.add(key, String.valueOf(value)));
        body.add("sign", sign);
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        fileHeaders.setContentDispositionFormData("file", fileName);
        body.add("file", new HttpEntity<>(new FileSystemResource(packagePath), fileHeaders));

        String responseBody = restClient.post()
                .uri(vivoBaseUrl(endpoint))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);

        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);
        Map<String, Object> data = asMap(response.get("data"));
        String storeReleaseId = firstString(data, "serialnumber", "serialNumber");
        if (!StringUtils.hasText(storeReleaseId)) {
            throw new IllegalStateException("Vivo upload succeeded but serialnumber is missing");
        }
        String message = firstString(response, "msg", "message");
        return new StoreSubmitResult(storeReleaseId, writeJson(requestLog), responseBody, StringUtils.hasText(message) ? message : "upload success");
    }

    private StoreReviewResult queryVivoStageDetails(AppStoreConfig storeConfig, AppReleaseRecord record) {
        if (!StringUtils.hasText(record.getPackageName())) {
            throw new IllegalArgumentException("Vivo staged release requires packageName for detail query");
        }
        if (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Vivo stage detail query requires clientId as access_key and clientSecret as access_secret");
        }

        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, VIVO_METHOD_QUERY_STAGE_DETAILS);
        payload.put("packageName", record.getPackageName());
        String sign = signVivoPayload(payload, storeConfig.getClientSecret());

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            Map<String, Object> mockResponse = new LinkedHashMap<>();
            mockResponse.put("code", 0);
            mockResponse.put("subCode", "0");
            mockResponse.put("msg", "mock success");
            mockResponse.put("data", Map.of(
                    "packageName", record.getPackageName(),
                    "auditStatus", 2,
                    "effectStatus", 1,
                    "stagedProportion", record.getGrayPercent() == null ? 0 : record.getGrayPercent()
            ));
            return new StoreReviewResult(ReleaseStatus.PASS, writeJson(mockResponse), null);
        }

        String responseBody = postVivoForm(endpoint, payload, sign);
        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);
        Map<String, Object> data = asMap(response.get("data"));
        int auditStatus = intValue(data.get("auditStatus"));
        int effectStatus = intValue(data.get("effectStatus"));
        ReleaseStatus releaseStatus = mapVivoStageStatus(auditStatus, effectStatus);
        String rejectReason = auditStatus == 1 ? firstString(data, "auditOpinion", "rejectReason") : null;
        return new StoreReviewResult(releaseStatus, responseBody, StringUtils.hasText(rejectReason) ? rejectReason : null);
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
        Map<String, Object> uploadResult = uploadOppoPackage(uploadConfigData, packagePath);
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
                    "appInfo", Map.of("errno", 0, "data", Map.of("audit_status_name", "\u5ba1\u6838\u901a\u8fc7", "audit_status", 2)),
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

        Integer versionCode = record.getVersionCode();
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

    private Map<String, Object> uploadOppoPackage(Map<String, Object> uploadConfigData, Path packagePath) {
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
            responseBody = restClient.put()
                    .uri(uploadUrl)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new FileSystemResource(packagePath))
                    .retrieve()
                    .body(String.class);
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
            responseBody = restClient.post()
                    .uri(uploadUrl)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);
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
            return restClient.get()
                    .uri(url + "?" + buildQueryString(payload))
                    .retrieve()
                    .body(String.class);
        }

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        payload.forEach((key, value) -> {
            if (value != null) {
                body.add(key, String.valueOf(value));
            }
        });
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    private Map<String, Object> buildVivoUploadPayload(AppStoreConfig storeConfig, AppVersion version, String fileMd5, boolean stageUpload) {
        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, VIVO_METHOD_UPLOAD_APK);
        payload.put("packageName", version.getAppInfo().getPackageName());
        payload.put("fileMd5", fileMd5);
        if (stageUpload) {
            payload.put("stageType", 1);
        }
        return payload;
    }

    private Map<String, Object> buildVivoStagePayload(
            AppStoreConfig storeConfig,
            AppVersion version,
            AppReleaseRecord record,
            String apkUploadSerialNumber
    ) {
        AppInfo appInfo = version.getAppInfo();
        String appName = appInfo == null ? null : appInfo.getAppName();
        String packageName = appInfo == null ? null : appInfo.getPackageName();
        String appDescription = appInfo == null ? null : appInfo.getAppDescription();

        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, VIVO_METHOD_CREATE_UPDATE_STAGE);
        payload.put("packageName", packageName);
        payload.put("subPackage", 0);
        payload.put("stagedStartTime", formatVivoDateTime(record.getGrayStartTime()));
        payload.put("stagedEndTime", formatVivoDateTime(record.getGrayEndTime()));
        payload.put("stagedProportion", record.getGrayPercent());
        payload.put("apkUuid64", apkUploadSerialNumber);
        payload.put("updateDesc", normalizeStageText(5, 200, version.getUpdateLog(), appDescription, appName, packageName, "版本更新说明"));
        payload.put("simpleDesc", normalizeStageText(10, 500, appDescription, version.getUpdateLog(), appName, packageName, "应用发布说明"));
        payload.put("description", normalizeStageText(20, 1000, appDescription, version.getUpdateLog(), appName, packageName, "应用详情说明"));
        payload.put("remark", normalizeStageText(5, 500, version.getUpdateLog(), appDescription, appName, packageName, "分阶段发布备注"));
        payload.put("mainTitle", normalizeTitle(appName, packageName));
        payload.put("subTitle", "");
        return payload;
    }

    private Map<String, Object> buildVivoCommonPayload(AppStoreConfig storeConfig, String method) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("access_key", storeConfig.getClientId());
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("method", method);
        payload.put("v", VIVO_PROTOCOL_VERSION);
        payload.put("sign_method", VIVO_SIGN_METHOD);
        payload.put("format", VIVO_FORMAT);
        payload.put("target_app_key", VIVO_TARGET_APP_KEY);
        return payload;
    }

    private String postVivoForm(StoreApiProperties.StoreEndpointProperties endpoint, Map<String, Object> payload, String sign) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        payload.forEach((key, value) -> body.add(key, value == null ? "" : String.valueOf(value)));
        body.add("sign", sign);
        return restClient.post()
                .uri(vivoBaseUrl(endpoint))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    private ReleaseStatus mapStatus(String value) {
        return switch (value.toLowerCase()) {
            case "pass", "approved", "online" -> ReleaseStatus.PASS;
            case "reject", "rejected", "failed" -> ReleaseStatus.REJECT;
            case "offline" -> ReleaseStatus.OFFLINE;
            default -> ReleaseStatus.AUDITING;
        };
    }

    private ReleaseStatus mapOppoStatus(Map<String, Object> appInfoData, Map<String, Object> taskStateData) {
        String auditStatusName = firstString(appInfoData, "audit_status_name", "auditStatusName");
        if (StringUtils.hasText(auditStatusName)) {
            if (auditStatusName.contains("\u9a73\u56de") || auditStatusName.contains("\u62d2\u7edd") || auditStatusName.contains("\u5931\u8d25")) {
                return ReleaseStatus.REJECT;
            }
            if (auditStatusName.contains("\u901a\u8fc7") || auditStatusName.contains("\u4e0a\u67b6") || auditStatusName.contains("\u4e0a\u7ebf")) {
                return ReleaseStatus.PASS;
            }
            if (auditStatusName.contains("\u4e0b\u67b6")) {
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

    private ReleaseStatus mapVivoStageStatus(int auditStatus, int effectStatus) {
        if (auditStatus == 1) {
            return ReleaseStatus.REJECT;
        }
        if (effectStatus == 4 || effectStatus == 5) {
            return ReleaseStatus.OFFLINE;
        }
        if (auditStatus == 2) {
            return ReleaseStatus.PASS;
        }
        return ReleaseStatus.AUDITING;
    }

    private String firstString(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private String writeJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            return String.valueOf(object);
        }
    }

    private Path requireLocalPackage(AppVersion version) {
        if (!StringUtils.hasText(version.getPackageUrl())) {
            throw new IllegalArgumentException("Package path is empty");
        }
        String packageLocation = version.getPackageUrl().trim();
        Path packagePath = toPath(packageLocation);
        if (packagePath != null && Files.exists(packagePath) && Files.isRegularFile(packagePath)) {
            return packagePath;
        }

        String downloadUrl = resolvePackageDownloadUrl(packageLocation, packagePath);
        if (!StringUtils.hasText(downloadUrl)) {
            throw new IllegalArgumentException("Package file not found: " + version.getPackageUrl());
        }
        return downloadPackageToLocal(downloadUrl, inferFileName(packageLocation));
    }

    private String signVivoPayload(Map<String, Object> payload, String accessSecret) {
        String source = payload.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        return hmacSha256Hex(source, accessSecret);
    }

    private String md5Hex(Path path) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            try (InputStream inputStream = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    messageDigest.update(buffer, 0, read);
                }
            }
            return toHex(messageDigest.digest());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read package file: " + path, ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 algorithm is not available", ex);
        }
    }

    private String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign request", ex);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            String hex = Integer.toHexString(value & 0xFF);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    private String vivoBaseUrl(StoreApiProperties.StoreEndpointProperties endpoint) {
        if (StringUtils.hasText(endpoint.getBaseUrl())) {
            return endpoint.getBaseUrl();
        }
        return endpoint.isSandboxEnabled() ? VIVO_SANDBOX_BASE_URL : VIVO_PRODUCTION_BASE_URL;
    }

    private Map<String, Object> readJson(String body) {
        if (!StringUtils.hasText(body)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unexpected vivo response: " + body, ex);
        }
    }

    private Map<String, Object> readJsonIfPossible(String body) {
        if (!StringUtils.hasText(body)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
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
        throw new IllegalStateException("Oppo " + action + " failed: errno=" + errno + ", msg=" + message);
    }

    private void ensureVivoSuccess(Map<String, Object> response) {
        int code = intValue(response.get("code"));
        String subCode = firstString(response, "subCode", "sub_code");
        if (code == 0 && (!StringUtils.hasText(subCode) || "0".equals(subCode))) {
            return;
        }
        String message = firstString(response, "msg", "message");
        throw new IllegalStateException(
                "Vivo upload failed: code=" + code
                        + ", subCode=" + (StringUtils.hasText(subCode) ? subCode : "-")
                        + ", msg=" + (StringUtils.hasText(message) ? message : "unknown error")
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private boolean isVivoStore(AppStoreConfig storeConfig) {
        return storeConfig.getStoreType() != null
                && "vivo".equalsIgnoreCase(storeConfig.getStoreType().getCode());
    }

    private boolean isOppoStore(AppStoreConfig storeConfig) {
        return storeConfig.getStoreType() != null
                && "oppo".equalsIgnoreCase(storeConfig.getStoreType().getCode());
    }

    private boolean isStagedRelease(AppReleaseRecord record) {
        return record != null && record.getReleaseType() != null && record.getReleaseType() == 2L;
    }

    private String resolvePackageDownloadUrl(String packageLocation, Path packagePath) {
        if (isHttpUrl(packageLocation)) {
            return packageLocation;
        }

        String baseUrl = appProperties.getPackageRepository().getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }

        String relativePath = resolveRepositoryRelativePath(packageLocation, packagePath);
        if (!StringUtils.hasText(relativePath)) {
            return null;
        }
        return joinUrl(baseUrl, relativePath);
    }

    private String resolveRepositoryRelativePath(String packageLocation, Path packagePath) {
        if (packagePath != null) {
            if (!packagePath.isAbsolute()) {
                return normalizeRepositoryPath(packagePath.toString());
            }

            try {
                Path storageRoot = Path.of(appProperties.getStorageRoot()).toAbsolutePath().normalize();
                Path normalizedPackagePath = packagePath.toAbsolutePath().normalize();
                if (normalizedPackagePath.startsWith(storageRoot)) {
                    return normalizeRepositoryPath(storageRoot.relativize(normalizedPackagePath).toString());
                }
            } catch (InvalidPathException ex) {
                log.debug("Skip storage root relative path resolution, packageLocation={}", packageLocation, ex);
            }

            Path fileName = packagePath.getFileName();
            return fileName == null ? null : normalizeRepositoryPath(fileName.toString());
        }
        return normalizeRepositoryPath(packageLocation);
    }

    private Path downloadPackageToLocal(String downloadUrl, String fileName) {
        String normalizedFileName = StringUtils.hasText(fileName) ? fileName : "package.apk";
        Path target = allocateDownloadedPackagePath(normalizedFileName);
        log.info("Download package from repository, url={}, target={}", downloadUrl, target);
        return restClient.get()
                .uri(downloadUrl)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("Failed to download package from repository: status=" + response.getStatusCode());
                    }
                    try (InputStream inputStream = response.getBody()) {
                        Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                        return target;
                    } catch (IOException ex) {
                        throw new IllegalStateException("Failed to save downloaded package: " + target, ex);
                    }
                });
    }

    private Path allocateDownloadedPackagePath(String fileName) {
        try {
            Path targetDir = Path.of(appProperties.getStorageRoot())
                    .toAbsolutePath()
                    .normalize()
                    .resolve("remote-cache")
                    .resolve(LocalDate.now().format(PACKAGE_CACHE_DATE_FORMATTER));
            Path requestDir = targetDir.resolve(UUID.randomUUID().toString());
            Files.createDirectories(requestDir);
            return requestDir.resolve(fileName);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create package cache directory", ex);
        }
    }

    private Path toPath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException ex) {
            return null;
        }
    }

    private boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private String inferFileName(String packageLocation) {
        String normalized = packageLocation.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
    }

    private String normalizeRepositoryPath(String value) {
        return value.replace('\\', '/').replaceFirst("^/+", "");
    }

    private String buildQueryString(Map<String, ?> params) {
        List<String> pairs = new ArrayList<>();
        params.forEach((key, value) -> {
            if (value != null) {
                pairs.add(encodeQueryParam(key) + "=" + encodeQueryParam(String.valueOf(value)));
            }
        });
        return String.join("&", pairs);
    }

    private String encodeQueryParam(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String joinUrl(String baseUrl, String relativePath) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedRelativePath = normalizeRepositoryPath(relativePath);
        return normalizedBaseUrl + "/" + normalizedRelativePath;
    }

    private String formatVivoDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return VIVO_DATE_TIME_FORMATTER.format(dateTime);
    }

    private String normalizeTitle(String appName, String packageName) {
        String title = firstNonBlank(appName, packageName, "应用发布");
        title = title.replaceAll("[\\r\\n\\t]", " ").trim();
        return title.length() <= 20 ? title : title.substring(0, 20);
    }

    private String normalizeStageText(int minLength, int maxLength, String... candidates) {
        String text = firstNonBlank(candidates);
        text = text.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        if (!StringUtils.hasText(text)) {
            text = "应用发布说明";
        }
        while (text.length() < minLength) {
            text = text + "，请关注更新内容";
        }
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength);
        }
        return text;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean isRejectStatus(Map<String, Object> appInfoData) {
        String auditStatusName = firstString(appInfoData, "audit_status_name", "auditStatusName");
        return StringUtils.hasText(auditStatusName)
                && (auditStatusName.contains("\u9a73\u56de") || auditStatusName.contains("\u62d2\u7edd"));
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

    @SuppressWarnings("unused")
    private LocalDateTime fromEpochMillis(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), DEFAULT_ZONE_ID);
    }
}
