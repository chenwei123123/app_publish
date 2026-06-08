package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.common.exception.StoreApiException;
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
import com.app.publishservice.util.ApkDownloadUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.InterruptedIOException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.net.URI;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class ConfigurableStorePublisher implements StorePublisher {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableStorePublisher.class);
    private static final String VIVO_PRODUCTION_BASE_URL = "https://developer-api.vivo.com.cn/router/rest";
    private static final String VIVO_SANDBOX_BASE_URL = "https://sandbox-developer-api.vivo.com.cn/router/rest";
    private static final String VIVO_METHOD_UPLOAD_APK_32 = "app.upload.apk.app.32";
    private static final String VIVO_METHOD_UPLOAD_APK_64 = "app.upload.apk.app.64";
    private static final String VIVO_METHOD_UPLOAD_ICON = "app.upload.icon";
    private static final String VIVO_METHOD_UPLOAD_SCREENSHOT = "app.upload.screenshot";
    private static final String VIVO_METHOD_CREATE_SUBPACKAGE_APP = "app.sync.create.subpackage.app";
    private static final String VIVO_METHOD_UPDATE_SUBPACKAGE_APP = "app.sync.update.subpackage.app";
    private static final String VIVO_METHOD_UPDATE_SUBMIT = VIVO_METHOD_UPDATE_SUBPACKAGE_APP;
    private static final String VIVO_METHOD_CREATE_UPDATE_STAGE = "app.sync.create.update.stage.app";
    private static final String VIVO_METHOD_QUERY_APP_DETAILS = "app.query.details";
    private static final String VIVO_METHOD_QUERY_STAGE_DETAILS = "app.query.stage.details";
    private static final String VIVO_SUB_CODE_APP_NOT_FOUND = "11001";
    private static final int VIVO_DEFAULT_COMPATIBLE_DEVICE = 1;
    private static final int VIVO_DEFAULT_RATE_AGE = 12;
    private static final int VIVO_MIN_SCREENSHOT_COUNT = 3;
    private static final int VIVO_MAX_SCREENSHOT_COUNT = 5;
    private static final String VIVO_TARGET_APP_KEY = "developer";
    private static final String VIVO_FORMAT = "json";
    private static final String VIVO_SIGN_METHOD = "hmac";
    private static final String VIVO_PROTOCOL_VERSION = "1.0";
    private static final String PROJECT_METADATA_FILE_NAME = "app-publish-metadata.json";
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
    private static final Charset GBK = Charset.forName("GBK");
    private static final String CMS_ARTIFACT_HOST = "artifacts.cmschina.com.cn";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final StoreRequestLogService storeRequestLogService;
    private final HttpClient packageHttpClient;

    public ConfigurableStorePublisher(RestClient restClient, ObjectMapper objectMapper, AppProperties appProperties) {
        this(restClient, objectMapper, appProperties, null);
    }

    @Autowired
    public ConfigurableStorePublisher(
            RestClient restClient,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            StoreRequestLogService storeRequestLogService
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.storeRequestLogService = storeRequestLogService;
        this.packageHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(appProperties.getStoreApi().getDefaultTimeoutSeconds(), 1)))
                .build();
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

        String tokenUrl = endpoint.getBaseUrl() + endpoint.getTokenEndpoint();
        log.info("Request store token, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeStoreRequest(
                trace(storeConfig, "request token for " + storeConfig.getStoreType().getCode(), "POST", tokenUrl, null, body),
                () -> restClient.post()
                        .uri(tokenUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class)
        );

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
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
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

            String submitUrl = endpoint.getBaseUrl() + endpoint.getSubmitEndpoint();
            log.info("Submit release to store endpoint, storeType={}, versionId={}", storeConfig.getStoreType().getCode(), version.getId());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = executeStoreRequest(
                    trace(
                            storeConfig,
                            "submit release to " + storeConfig.getStoreType().getCode(),
                            "POST",
                            submitUrl,
                            null,
                            requestPayload(payload, Map.of("Authorization", "Bearer " + token))
                    ),
                    () -> restClient.post()
                            .uri(submitUrl)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .body(payload)
                            .retrieve()
                            .body(Map.class)
            );

            String storeReleaseId = firstString(response, "storeReleaseId", "id");
            return new StoreSubmitResult(storeReleaseId, writeJson(payload), writeJson(response), "submit success");
        }
    }

    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        log.debug("Start query review, releaseId={}, storeType={}, releaseType={}", record.getId(), record.getStoreType(), record.getReleaseType());
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            if (isVivoStore(storeConfig) && isStagedRelease(record)) {
                return queryVivoStageDetails(storeConfig, record);
            }
            if (isVivoStore(storeConfig)) {
                return queryVivoAppDetails(storeConfig, record);
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
            Map<String, Object> response = executeStoreRequest(
                    trace(
                            storeConfig,
                            "query review for " + storeConfig.getStoreType().getCode(),
                            "GET",
                            statusUrl,
                            Map.of("id", record.getStoreReleaseId()),
                            Map.of("Authorization", "Bearer " + token)
                    ),
                    () -> restClient.get()
                            .uri(statusUrl)
                            .header("Authorization", "Bearer " + token)
                            .retrieve()
                            .body(Map.class)
            );

            Object statusCode = response.get("status");
            ReleaseStatus releaseStatus = mapStatus(statusCode == null ? "auditing" : statusCode.toString());
            String rejectReason = response.get("rejectReason") == null ? null : response.get("rejectReason").toString();
            return new StoreReviewResult(releaseStatus, writeJson(response), rejectReason);
        }
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

    private StoreSubmitResult submitVivoRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record) {
        VivoAppDetailsLookupResult appDetailsLookup = ensureVivoSubmitReady(storeConfig, version);
        if (appDetailsLookup.appMissing()) {
            ensureVivoCreateAllowed(storeConfig);
            return submitVivoCreateApp(storeConfig, version, appDetailsLookup);
        }
        VivoAppDetailsResult appDetails = appDetailsLookup.appDetails();
        if (isStagedRelease(record)) {
            return submitVivoStageRelease(storeConfig, version, record, appDetails);
        }
        return submitVivoFullRelease(storeConfig, version, appDetails);
    }

    private StoreSubmitResult submitVivoFullRelease(AppStoreConfig storeConfig, AppVersion version, VivoAppDetailsResult appDetails) {
        VivoPackageUploadBundle uploadBundle = uploadVivoPackageBundle(storeConfig, version, false);
        Map<String, Object> payload = buildVivoSubpackageUpdatePayload(
                storeConfig,
                version,
                uploadBundle.apk32UploadResult().serialNumber(),
                uploadBundle.apk64UploadResult().serialNumber()
        );
        String sign = signVivoPayload(payload, storeConfig.getClientSecret());
        Map<String, Object> submitRequestLog = new LinkedHashMap<>(payload);
        submitRequestLog.put("sign", sign);

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            Map<String, Object> requestLog = new LinkedHashMap<>();
            requestLog.put("appDetailsQuery", readJson(appDetails.requestLog()));
            requestLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().requestLog()));
            requestLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().requestLog()));
            requestLog.put("appSubmit", submitRequestLog);
            Map<String, Object> responseLog = new LinkedHashMap<>();
            responseLog.put("appDetailsQuery", readJson(appDetails.responseLog()));
            responseLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().responseLog()));
            responseLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().responseLog()));
            responseLog.put("appSubmit", Map.of("code", 0, "subCode", "0", "msg", "mock app submit success"));
            return new StoreSubmitResult(
                    uploadBundle.apk64UploadResult().serialNumber(),
                    writeJson(requestLog),
                    writeJson(responseLog),
                    "mock app submit success"
            );
        }

        log.info(
                "Submit vivo subpackage app update, versionId={}, packageName={}, versionName={}, versionCode={}",
                version.getId(),
                version.getAppInfo().getPackageName(),
                version.getVersionName(),
                version.getVersionCode()
        );
        String responseBody = postVivoForm(storeConfig, endpoint, payload, sign);
        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("appDetailsQuery", readJson(appDetails.requestLog()));
        requestLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().requestLog()));
        requestLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().requestLog()));
        requestLog.put("appSubmit", submitRequestLog);
        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("appDetailsQuery", readJson(appDetails.responseLog()));
        responseLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().responseLog()));
        responseLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().responseLog()));
        responseLog.put("appSubmit", response);
        String message = firstString(response, "msg", "message");
        return new StoreSubmitResult(
                uploadBundle.apk64UploadResult().serialNumber(),
                writeJson(requestLog),
                writeJson(responseLog),
                StringUtils.hasText(message) ? message : "app submit success"
        );
    }

    private StoreSubmitResult submitVivoStageRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, VivoAppDetailsResult appDetails) {
        VivoPackageUploadBundle uploadBundle = uploadVivoPackageBundle(storeConfig, version, true);
        Map<String, Object> payload = buildVivoSubpackageStagePayload(
                storeConfig,
                version,
                record,
                uploadBundle.apk32UploadResult().serialNumber(),
                uploadBundle.apk64UploadResult().serialNumber()
        );
        String sign = signVivoPayload(payload, storeConfig.getClientSecret());
        Map<String, Object> stageRequestLog = new LinkedHashMap<>(payload);
        stageRequestLog.put("sign", sign);

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            Map<String, Object> requestLog = new LinkedHashMap<>();
            requestLog.put("appDetailsQuery", readJson(appDetails.requestLog()));
            requestLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().requestLog()));
            requestLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().requestLog()));
            requestLog.put("stageSubmit", stageRequestLog);
            Map<String, Object> responseLog = new LinkedHashMap<>();
            responseLog.put("appDetailsQuery", readJson(appDetails.responseLog()));
            responseLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().responseLog()));
            responseLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().responseLog()));
            responseLog.put("stageSubmit", Map.of("code", 0, "subCode", "0", "msg", "mock stage submit success"));
            return new StoreSubmitResult(
                    uploadBundle.apk64UploadResult().serialNumber(),
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
        String responseBody = postVivoForm(storeConfig, endpoint, payload, sign);
        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("appDetailsQuery", readJson(appDetails.requestLog()));
        requestLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().requestLog()));
        requestLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().requestLog()));
        requestLog.put("stageSubmit", stageRequestLog);
        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("appDetailsQuery", readJson(appDetails.responseLog()));
        responseLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().responseLog()));
        responseLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().responseLog()));
        responseLog.put("stageSubmit", response);
        String message = firstString(response, "msg", "message");
        return new StoreSubmitResult(
                uploadBundle.apk64UploadResult().serialNumber(),
                writeJson(requestLog),
                writeJson(responseLog),
                StringUtils.hasText(message) ? message : "stage submit success"
        );
    }

    private VivoAppDetailsLookupResult ensureVivoSubmitReady(AppStoreConfig storeConfig, AppVersion version) {
        AppInfo appInfo = version.getAppInfo();
        if (appInfo == null || !StringUtils.hasText(appInfo.getPackageName())) {
            throw new IllegalArgumentException("Vivo submit requires app packageName");
        }
        VivoAppDetailsLookupResult appDetailsLookup = queryVivoAppDetailsForSubmit(storeConfig, appInfo.getPackageName());
        if (appDetailsLookup.appMissing()) {
            log.info("Vivo app does not exist yet, switch to create flow, packageName={}", appInfo.getPackageName());
            return appDetailsLookup;
        }
        VivoAppDetailsResult appDetails = appDetailsLookup.appDetails();
        if (isVivoAppApprovedAndOnline(appDetails.status(), appDetails.saleStatus())) {
            return appDetailsLookup;
        }

        StringBuilder message = new StringBuilder("Vivo submit requires app.query.details to return saleStatus=1 and status=3");
        message.append(", packageName=").append(appInfo.getPackageName());
        message.append(", current saleStatus=").append(appDetails.saleStatus());
        message.append(", current status=").append(appDetails.status());
        if (StringUtils.hasText(appDetails.rejectReason())) {
            message.append(", rejectReason=").append(appDetails.rejectReason());
        }
        throw new IllegalStateException(message.toString());
    }

    private StoreSubmitResult submitVivoCreateApp(
            AppStoreConfig storeConfig,
            AppVersion version,
            VivoAppDetailsLookupResult appDetailsLookup
    ) {
        VivoPackageBundle packageBundle = resolveVivoPackageBundle(version);
        VivoCreateContext createContext = resolveVivoCreateContext(version, packageBundle.metadataContext());
        VivoPackageUploadBundle uploadBundle = uploadVivoPackageBundle(storeConfig, version, packageBundle, false);
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        
            // 等待 10 秒，让 vivo 沙箱完成 APK 解析
        waitForVivoPackageParsing(endpoint);
        
        VivoUploadResult iconUploadResult = uploadVivoAsset(storeConfig, createContext.packageName(), createContext.iconPath(), VIVO_METHOD_UPLOAD_ICON, "icon");
        List<VivoUploadResult> screenshotUploadResults = new ArrayList<>();
        for (Path screenshotPath : createContext.screenshotPaths()) {
            screenshotUploadResults.add(uploadVivoAsset(
                    storeConfig,
                    createContext.packageName(),
                    screenshotPath,
                    VIVO_METHOD_UPLOAD_SCREENSHOT,
                    "screenshot"
            ));
        }

        Map<String, Object> payload = buildVivoSubpackageCreatePayload(
                storeConfig,
                version,
                createContext,
                uploadBundle.apk32UploadResult().serialNumber(),
                uploadBundle.apk64UploadResult().serialNumber(),
                iconUploadResult.serialNumber(),
                buildVivoCreateScreenshotValue(screenshotUploadResults.stream().map(VivoUploadResult::serialNumber).toList())
        );
        String sign = signVivoPayload(payload, storeConfig.getClientSecret());
        Map<String, Object> createRequestLog = new LinkedHashMap<>(payload);
        createRequestLog.put("sign", sign);

        if (endpoint.isMockEnabled()) {
            Map<String, Object> requestLog = new LinkedHashMap<>();
            requestLog.put("appDetailsQuery", readJson(appDetailsLookup.requestLog()));
            requestLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().requestLog()));
            requestLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().requestLog()));
            requestLog.put("iconUpload", readJson(iconUploadResult.requestLog()));
            requestLog.put("screenshotUpload", screenshotUploadResults.stream().map(result -> readJson(result.requestLog())).toList());
            requestLog.put("appCreate", createRequestLog);
            Map<String, Object> responseLog = new LinkedHashMap<>();
            responseLog.put("appDetailsQuery", readJson(appDetailsLookup.responseLog()));
            responseLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().responseLog()));
            responseLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().responseLog()));
            responseLog.put("iconUpload", readJson(iconUploadResult.responseLog()));
            responseLog.put("screenshotUpload", screenshotUploadResults.stream().map(result -> readJson(result.responseLog())).toList());
            responseLog.put("appCreate", Map.of("code", 0, "subCode", "0", "msg", "mock app create success"));
            return new StoreSubmitResult(
                    createContext.packageName(),
                    writeJson(requestLog),
                    writeJson(responseLog),
                    "mock app create success"
            );
        }

        log.info(
                "Create vivo subpackage app, versionId={}, packageName={}, versionName={}, versionCode={}",
                version.getId(),
                createContext.packageName(),
                version.getVersionName(),
                version.getVersionCode()
        );
        String responseBody = postVivoForm(storeConfig, endpoint, payload, sign);
        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("appDetailsQuery", readJson(appDetailsLookup.requestLog()));
        requestLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().requestLog()));
        requestLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().requestLog()));
        requestLog.put("iconUpload", readJson(iconUploadResult.requestLog()));
        requestLog.put("screenshotUpload", screenshotUploadResults.stream().map(result -> readJson(result.requestLog())).toList());
        requestLog.put("appCreate", createRequestLog);
        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("appDetailsQuery", readJson(appDetailsLookup.responseLog()));
        responseLog.put("apk32Upload", readJson(uploadBundle.apk32UploadResult().responseLog()));
        responseLog.put("apk64Upload", readJson(uploadBundle.apk64UploadResult().responseLog()));
        responseLog.put("iconUpload", readJson(iconUploadResult.responseLog()));
        responseLog.put("screenshotUpload", screenshotUploadResults.stream().map(result -> readJson(result.responseLog())).toList());
        responseLog.put("appCreate", response);
        String message = firstString(response, "msg", "message");
        return new StoreSubmitResult(
                createContext.packageName(),
                writeJson(requestLog),
                writeJson(responseLog),
                StringUtils.hasText(message) ? message : "app create success"
        );
    }

    private VivoPackageUploadBundle uploadVivoPackageBundle(AppStoreConfig storeConfig, AppVersion version, boolean stageUpload) {
        return uploadVivoPackageBundle(storeConfig, version, resolveVivoPackageBundle(version), stageUpload);
    }

    private VivoPackageUploadBundle uploadVivoPackageBundle(
            AppStoreConfig storeConfig,
            AppVersion version,
            VivoPackageBundle packageBundle,
            boolean stageUpload
    ) {
        VivoUploadResult apk32UploadResult = submitVivoApkUpload(
                storeConfig,
                version,
                packageBundle.apk32Source(),
                stageUpload,
                VIVO_METHOD_UPLOAD_APK_32,
                "32-bit apk"
        );
        VivoUploadResult apk64UploadResult = submitVivoApkUpload(
                storeConfig,
                version,
                packageBundle.apk64Source(),
                stageUpload,
                VIVO_METHOD_UPLOAD_APK_64,
                "64-bit apk"
        );
        return new VivoPackageUploadBundle(apk32UploadResult, apk64UploadResult);
    }

    private VivoUploadResult submitVivoApkUpload(
            AppStoreConfig storeConfig,
            AppVersion version,
            PackageContentSource packageSource,
            boolean stageUpload,
            String uploadMethod,
            String uploadLabel
    ) {
        String fileName = packageSource.fileName();
        if (!fileName.toLowerCase().endsWith(".apk")) {
            throw new IllegalArgumentException("Vivo upload only supports APK packages");
        }
        if (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Vivo upload requires clientId as access_key and clientSecret as access_secret");
        }

        String fileMd5 = md5Hex(packageSource);
        Map<String, Object> payload = buildVivoUploadPayload(storeConfig, version, fileMd5, uploadMethod, stageUpload);
        String sign = signVivoPayload(payload, storeConfig.getClientSecret());
        Map<String, Object> requestLog = new LinkedHashMap<>(payload);
        requestLog.put("sign", sign);
        requestLog.put("fileName", fileName);
        requestLog.put("fileSource", packageSource.sourceType());
        if (StringUtils.hasText(packageSource.remoteUrl())) {
            requestLog.put("downloadUrl", packageSource.remoteUrl());
        }

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            log.info("Use mock vivo {} upload, versionId={}, fileName={}, stageUpload={}", uploadLabel, version.getId(), fileName, stageUpload);
            String storeReleaseId = "vivo-" + UUID.randomUUID();
            return new VivoUploadResult(
                    storeReleaseId,
                    writeJson(requestLog),
                    "{\"code\":0,\"subCode\":\"0\",\"msg\":\"mock upload success\",\"data\":{\"serialnumber\":\"" + storeReleaseId + "\"}}"
            );
        }

        log.info(
                "Submit vivo {} upload, versionId={}, fileName={}, packageName={}, stageUpload={}",
                uploadLabel,
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
        body.add("file", new HttpEntity<>(uploadResource(packageSource), fileHeaders));

        byte[] responseBytes = executeStoreRequest(
                trace(storeConfig, "upload vivo " + uploadLabel, "POST", vivoBaseUrl(endpoint), null, requestLog),
                () -> restClient.post()
                        .uri(vivoBaseUrl(endpoint))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .retrieve()
                        .body(byte[].class)
        );
        String responseBody = decodeVivoResponseBody(responseBytes);

        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);
        Map<String, Object> data = asMap(response.get("data"));
        String storeReleaseId = firstString(data, "serialnumber", "serialNumber");
        if (!StringUtils.hasText(storeReleaseId)) {
            throw new IllegalStateException("Vivo upload succeeded but serialnumber is missing");
        }
        return new VivoUploadResult(storeReleaseId, writeJson(requestLog), responseBody);
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

        String responseBody = postVivoForm(storeConfig, endpoint, payload, sign);
        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);
        Map<String, Object> data = asMap(response.get("data"));
        int auditStatus = intValue(data.get("auditStatus"));
        int effectStatus = intValue(data.get("effectStatus"));
        ReleaseStatus releaseStatus = mapVivoStageStatus(auditStatus, effectStatus);
        String rejectReason = auditStatus == 1 ? firstString(data, "auditOpinion", "rejectReason") : null;
        return new StoreReviewResult(releaseStatus, responseBody, StringUtils.hasText(rejectReason) ? rejectReason : null);
    }

    private StoreReviewResult queryVivoAppDetails(AppStoreConfig storeConfig, AppReleaseRecord record) {
        VivoAppDetailsResult appDetails = queryVivoAppDetails(storeConfig, record.getPackageName());
        return new StoreReviewResult(appDetails.releaseStatus(), appDetails.responseLog(), appDetails.rejectReason());
    }

    private VivoAppDetailsResult queryVivoAppDetails(AppStoreConfig storeConfig, String packageName) {
        if (!StringUtils.hasText(packageName)) {
            throw new IllegalArgumentException("Vivo release requires packageName for detail query");
        }
        if (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Vivo app detail query requires clientId as access_key and clientSecret as access_secret");
        }

        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, VIVO_METHOD_QUERY_APP_DETAILS);
        payload.put("packageName", packageName);
        String sign = signVivoPayload(payload, storeConfig.getClientSecret());
        Map<String, Object> requestLog = new LinkedHashMap<>(payload);
        requestLog.put("sign", sign);

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            Map<String, Object> mockResponse = new LinkedHashMap<>();
            mockResponse.put("code", 0);
            mockResponse.put("subCode", "0");
            mockResponse.put("msg", "mock success");
            mockResponse.put("data", Map.of(
                    "packageName", packageName,
                    "status", 3,
                    "saleStatus", 1
            ));
            return new VivoAppDetailsResult(3, 1, ReleaseStatus.PASS, null, writeJson(requestLog), writeJson(mockResponse));
        }

        String responseBody = postVivoForm(storeConfig, endpoint, payload, sign);
        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);
        return toVivoAppDetailsResult(response, writeJson(requestLog), responseBody);
    }

    private VivoAppDetailsLookupResult queryVivoAppDetailsForSubmit(AppStoreConfig storeConfig, String packageName) {
        if (!StringUtils.hasText(packageName)) {
            throw new IllegalArgumentException("Vivo release requires packageName for detail query");
        }
        if (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Vivo app detail query requires clientId as access_key and clientSecret as access_secret");
        }

        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, VIVO_METHOD_QUERY_APP_DETAILS);
        payload.put("packageName", packageName);
        String sign = signVivoPayload(payload, storeConfig.getClientSecret());
        Map<String, Object> requestLog = new LinkedHashMap<>(payload);
        requestLog.put("sign", sign);

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            Map<String, Object> mockResponse = new LinkedHashMap<>();
            mockResponse.put("code", 0);
            mockResponse.put("subCode", "0");
            mockResponse.put("msg", "mock success");
            mockResponse.put("data", Map.of(
                    "packageName", packageName,
                    "status", 3,
                    "saleStatus", 1
            ));
            VivoAppDetailsResult appDetails = new VivoAppDetailsResult(3, 1, ReleaseStatus.PASS, null, writeJson(requestLog), writeJson(mockResponse));
            return new VivoAppDetailsLookupResult(false, appDetails, writeJson(requestLog), writeJson(mockResponse));
        }

        String responseBody = postVivoForm(storeConfig, endpoint, payload, sign);
        Map<String, Object> response = readJson(responseBody);
        if (isVivoAppNotFoundResponse(response)) {
            return new VivoAppDetailsLookupResult(true, null, writeJson(requestLog), responseBody);
        }
        ensureVivoSuccess(response);
        return new VivoAppDetailsLookupResult(
                false,
                toVivoAppDetailsResult(response, writeJson(requestLog), responseBody),
                writeJson(requestLog),
                responseBody
        );
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

    private Map<String, Object> buildVivoUploadPayload(
            AppStoreConfig storeConfig,
            AppVersion version,
            String fileMd5,
            String uploadMethod,
            boolean stageUpload
    ) {
        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, uploadMethod);
        payload.put("packageName", version.getAppInfo().getPackageName());
        payload.put("fileMd5", fileMd5);
        if (stageUpload) {
            payload.put("stageType", 1);
        }
        return payload;
    }

    private Map<String, Object> buildVivoCreateAppPayload(
            AppStoreConfig storeConfig,
            AppVersion version,
            VivoCreateContext createContext,
            String apk32SerialNumber,
            String apk64SerialNumber,
            String iconSerialNumber,
            String screenshotValue
    ) {
        AppInfo appInfo = version.getAppInfo();
        String appName = appInfo == null ? null : appInfo.getAppName();
        String packageName = appInfo == null ? null : appInfo.getPackageName();
        String appDescription = appInfo == null ? null : appInfo.getAppDescription();

        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, VIVO_METHOD_CREATE_SUBPACKAGE_APP);
        payload.put("packageName", packageName);
        payload.put("apk32", apk32SerialNumber);
        payload.put("apk64", apk64SerialNumber);
        payload.put("versionCode",version.getVersionCode());
        payload.put("privacyUrl",appInfo.getPrivacyUrl());
        payload.put("privateSelfCheck",appInfo.getPrivacyUrl());
        payload.put("onlineType", 1);
        payload.put("updateDesc", normalizeStageText(5, 200, version.getUpdateLog(), appDescription, appName, packageName, "版本更新说明"));
        payload.put("detailDesc", normalizeStageText(50, 1000, appDescription, version.getUpdateLog(), appName, packageName, "应用简介"));
        payload.put("icon", iconSerialNumber);
        payload.put("screenshot", screenshotValue);
        payload.put("appClassify", createContext.appClassify());
        payload.put("subAppClassify", createContext.subAppClassify());
        //payload.put("remark", normalizeStageText(10, 200, version.getUpdateLog(), appDescription, appName, packageName, "应用创建审核留言"));
        payload.put("mainTitle", normalizeTitle(appName, packageName));
        payload.put("subTitle", "");
        payload.put("compatibleDevice", createContext.compatibleDevice());
        payload.put("rateAge", createContext.rateAge());
        //payload.put("simpleDesc", normalizeStageText(5, 80, appDescription, version.getUpdateLog(), appName, packageName, "应用简介"));
        return payload;
    }

    private Map<String, Object> buildVivoUpdateSubmitPayload(
            AppStoreConfig storeConfig,
            AppVersion version,
            String apkSerialNumber,
            String fileMd5
    ) {
        AppInfo appInfo = version.getAppInfo();
        String appName = appInfo == null ? null : appInfo.getAppName();
        String packageName = appInfo == null ? null : appInfo.getPackageName();
        String appDescription = appInfo == null ? null : appInfo.getAppDescription();

        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, VIVO_METHOD_UPDATE_SUBMIT);
        payload.put("packageName", packageName);
        payload.put("versionCode", version.getVersionCode());
        payload.put("apk", apkSerialNumber);
        payload.put("fileMd5", fileMd5);
        payload.put("onlineType", 1);
        payload.put("updateDesc", normalizeStageText(5, 200, version.getUpdateLog(), appDescription, appName, packageName, "版本更新说明"));
        payload.put("detailDesc", normalizeStageText(50, 1000, appDescription, version.getUpdateLog(), appName, packageName, "应用简介"));
        payload.put("mainTitle", normalizeTitle(appName, packageName));
        payload.put("subTitle", "");
        payload.put("remark", normalizeStageText(10, 200, version.getUpdateLog(), appDescription, appName, packageName, "应用更新审核留言"));
        payload.put("compatibleDevice", 1);
        //todo
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isSandboxEnabled()) {
//            payload.put("appClassify",9);
           payload.put("subAppClassify",3);
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

    private Map<String, Object> buildVivoSubpackageCreatePayload(
            AppStoreConfig storeConfig,
            AppVersion version,
            VivoCreateContext createContext,
            String apk32SerialNumber,
            String apk64SerialNumber,
            String iconSerialNumber,
            String screenshotValue
    ) {
        AppInfo appInfo = version.getAppInfo();
        String appName = appInfo == null ? null : appInfo.getAppName();
        String packageName = appInfo == null ? null : appInfo.getPackageName();
        String appDescription = appInfo == null ? null : appInfo.getAppDescription();

        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, VIVO_METHOD_CREATE_SUBPACKAGE_APP);
        payload.put("packageName", packageName);
        payload.put("apk32", apk32SerialNumber);
        payload.put("apk64", apk64SerialNumber);
        payload.put("onlineType", 1);
        payload.put("updateDesc", normalizeStageText(5, 200, version.getUpdateLog(), appDescription, appName, packageName, "Version update notes"));
        payload.put("detailDesc", normalizeStageText(50, 1000, appDescription, version.getUpdateLog(), appName, packageName, "App description"));
        payload.put("icon", iconSerialNumber);
        payload.put("screenshot", screenshotValue);
        payload.put("appClassify", createContext.appClassify());
        payload.put("subAppClassify", createContext.subAppClassify());
        payload.put("compatibleDevice", createContext.compatibleDevice());
        payload.put("rateAge", createContext.rateAge());
        return payload;
    }

    private Map<String, Object> buildVivoSubpackageUpdatePayload(
            AppStoreConfig storeConfig,
            AppVersion version,
            String apk32SerialNumber,
            String apk64SerialNumber
    ) {
        AppInfo appInfo = version.getAppInfo();
        String appName = appInfo == null ? null : appInfo.getAppName();
        String packageName = appInfo == null ? null : appInfo.getPackageName();
        String appDescription = appInfo == null ? null : appInfo.getAppDescription();

        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, VIVO_METHOD_UPDATE_SUBPACKAGE_APP);
        payload.put("packageName", packageName);
        payload.put("versionCode", version.getVersionCode());
        payload.put("apk32", apk32SerialNumber);
        payload.put("apk64", apk64SerialNumber);
        payload.put("onlineType", 1);
        payload.put("updateDesc", normalizeStageText(5, 200, version.getUpdateLog(), appDescription, appName, packageName, "Version update notes"));
        payload.put("detailDesc", normalizeStageText(50, 1000, appDescription, version.getUpdateLog(), appName, packageName, "App description"));
        payload.put("mainTitle", normalizeTitle(appName, packageName));
        payload.put("subTitle", "");
        payload.put("remark", normalizeStageText(10, 200, version.getUpdateLog(), appDescription, appName, packageName, "App update review notes"));
        payload.put("compatibleDevice", VIVO_DEFAULT_COMPATIBLE_DEVICE);
        return payload;
    }

    private Map<String, Object> buildVivoSubpackageStagePayload(
            AppStoreConfig storeConfig,
            AppVersion version,
            AppReleaseRecord record,
            String apk32UploadSerialNumber,
            String apk64UploadSerialNumber
    ) {
        AppInfo appInfo = version.getAppInfo();
        String appName = appInfo == null ? null : appInfo.getAppName();
        String packageName = appInfo == null ? null : appInfo.getPackageName();
        String appDescription = appInfo == null ? null : appInfo.getAppDescription();

        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, VIVO_METHOD_CREATE_UPDATE_STAGE);
        payload.put("packageName", packageName);
        payload.put("subPackage", 1);
        payload.put("stagedStartTime", formatVivoDateTime(record.getGrayStartTime()));
        payload.put("stagedEndTime", formatVivoDateTime(record.getGrayEndTime()));
        payload.put("stagedProportion", record.getGrayPercent());
        payload.put("apkUuid32", apk32UploadSerialNumber);
        payload.put("apkUuid64", apk64UploadSerialNumber);
        payload.put("updateDesc", normalizeStageText(5, 200, version.getUpdateLog(), appDescription, appName, packageName, "Version update notes"));
        payload.put("simpleDesc", normalizeStageText(10, 500, appDescription, version.getUpdateLog(), appName, packageName, "App release notes"));
        payload.put("description", normalizeStageText(20, 1000, appDescription, version.getUpdateLog(), appName, packageName, "App detail description"));
        payload.put("remark", normalizeStageText(5, 500, version.getUpdateLog(), appDescription, appName, packageName, "Stage release notes"));
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

    private VivoUploadResult uploadVivoAsset(
            AppStoreConfig storeConfig,
            String packageName,
            Path filePath,
            String method,
            String assetType
    ) {
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Vivo " + assetType + " file not found: " + filePath);
        }
        if (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Vivo " + assetType + " upload requires clientId as access_key and clientSecret as access_secret");
        }

        Map<String, Object> payload = buildVivoCommonPayload(storeConfig, method);
        payload.put("packageName", packageName);
        String sign = signVivoPayload(payload, storeConfig.getClientSecret());
        Map<String, Object> requestLog = new LinkedHashMap<>(payload);
        requestLog.put("sign", sign);
        requestLog.put("fileName", filePath.getFileName().toString());

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            String serialNumber = assetType + "-" + UUID.randomUUID();
            return new VivoUploadResult(
                    serialNumber,
                    writeJson(requestLog),
                    "{\"code\":0,\"subCode\":\"0\",\"msg\":\"mock " + assetType + " upload success\",\"data\":{\"serialnumber\":\"" + serialNumber + "\"}}"
            );
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        payload.forEach((key, value) -> body.add(key, value == null ? "" : String.valueOf(value)));
        body.add("sign", sign);
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        fileHeaders.setContentDispositionFormData("file", filePath.getFileName().toString());
        body.add("file", new HttpEntity<>(new FileSystemResource(filePath), fileHeaders));

        byte[] responseBytes = executeStoreRequest(
                trace(storeConfig, "upload vivo " + assetType, "POST", vivoBaseUrl(endpoint), null, requestLog),
                () -> restClient.post()
                        .uri(vivoBaseUrl(endpoint))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .retrieve()
                        .body(byte[].class)
        );
        String responseBody = decodeVivoResponseBody(responseBytes);
        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);
        Map<String, Object> data = asMap(response.get("data"));
        String serialNumber = firstString(data, "serialnumber", "serialNumber");
        if (!StringUtils.hasText(serialNumber)) {
            throw new IllegalStateException("Vivo " + assetType + " upload succeeded but serialnumber is missing");
        }
        return new VivoUploadResult(serialNumber, writeJson(requestLog), responseBody);
    }

    private VivoPackageBundle resolveVivoPackageBundle(AppVersion version) {
        String defaultApk64Location = firstNonBlank(version.getPackageUrlHigh(), version.getPackageUrl());
        ProjectMetadataContext metadataContext = resolveProjectMetadataContext(defaultApk64Location);
        Map<String, Object> metadata = metadataContext.metadata();

        Path apk32Path = resolveProjectAssetPath(
                metadataContext.metadataPath(),
                firstNonNull(
                        metadataLookup(metadata, "vivo", "apk32Path"),
                        metadataLookup(metadata, null, "vivoApk32Path"),
                        metadataLookup(metadata, "vivo", "apk32")
                )
        );
        Path apk64Path = resolveProjectAssetPath(
                metadataContext.metadataPath(),
                firstNonNull(
                        metadataLookup(metadata, "vivo", "apk64Path"),
                        metadataLookup(metadata, null, "vivoApk64Path"),
                        metadataLookup(metadata, "vivo", "apk64")
                )
        );
        PackageContentSource apk32Source = apk32Path == null
                ? null
                : new PackageContentSource(apk32Path.getFileName().toString(), apk32Path, null);
        if (apk32Source == null && StringUtils.hasText(version.getPackageUrlLow())) {
            apk32Source = resolveVivoPackageSource(version.getPackageUrlLow(), "32-bit package path is empty");
        }
        PackageContentSource apk64Source = apk64Path == null
                ? resolveVivoPackageSource(defaultApk64Location, "64-bit package path is empty")
                : new PackageContentSource(apk64Path.getFileName().toString(), apk64Path, null);
        if (apk32Source == null) {
            throw new IllegalStateException("Vivo subpackage submit requires 32-bit apk. Provide vivo.apk32Path in project app-publish-metadata.json.");
        }
        return new VivoPackageBundle(apk32Source, apk64Source, metadataContext);
    }

    private VivoCreateContext resolveVivoCreateContext(AppVersion version, ProjectMetadataContext metadataContext) {
        AppInfo appInfo = version.getAppInfo();
        if (appInfo == null || !StringUtils.hasText(appInfo.getPackageName())) {
            throw new IllegalArgumentException("Vivo create app requires app packageName");
        }

        Map<String, Object> metadata = metadataContext.metadata();
        String packageName = appInfo.getPackageName();
        Path iconPath = resolveVivoIconPath(metadataContext, metadata);
        List<Path> screenshotPaths = resolveVivoScreenshotPaths(metadataContext, metadata);
        if (iconPath == null) {
            throw new IllegalStateException("Vivo app create requires icon asset. Provide vivo.iconPath in project app-publish-metadata.json.");
        }
        validateVivoScreenshotCount(screenshotPaths.size(), "Vivo app create requires 3 to 5 screenshots. Configure vivo.screenshotPaths in project app-publish-metadata.json.");

        Integer appClassify = firstInteger(metadataLookup(metadata, "vivo", "appClassify"), metadataLookup(metadata, null, "appClassify"));
        Integer subAppClassify = firstInteger(metadataLookup(metadata, "vivo", "subAppClassify"), metadataLookup(metadata, null, "subAppClassify"));
        if (appClassify == null || subAppClassify == null) {
            throw new IllegalStateException("Vivo app create requires appClassify and subAppClassify in project app-publish-metadata.json.");
        }
        Integer compatibleDevice = firstInteger(metadataLookup(metadata, "vivo", "compatibleDevice"), VIVO_DEFAULT_COMPATIBLE_DEVICE);
        Integer rateAge = firstInteger(metadataLookup(metadata, "vivo", "rateAge"), VIVO_DEFAULT_RATE_AGE);
        return new VivoCreateContext(
                packageName,
                iconPath,
                screenshotPaths,
                appClassify,
                subAppClassify,
                compatibleDevice == null ? VIVO_DEFAULT_COMPATIBLE_DEVICE : compatibleDevice,
                rateAge == null ? VIVO_DEFAULT_RATE_AGE : rateAge
        );
    }

    private void waitForVivoPackageParsing(StoreApiProperties.StoreEndpointProperties endpoint) {
        String baseUrl = vivoBaseUrl(endpoint);
        if (baseUrl.startsWith("http://127.0.0.1") || baseUrl.startsWith("http://localhost")) {
            return;
        }
        try {
            Thread.sleep(10000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String postVivoForm(AppStoreConfig storeConfig, StoreApiProperties.StoreEndpointProperties endpoint, Map<String, Object> payload, String sign) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        payload.forEach((key, value) -> body.add(key, value == null ? "" : String.valueOf(value)));
        body.add("sign", sign);
        Map<String, Object> requestLog = new LinkedHashMap<>(payload);
        requestLog.put("sign", sign);
        byte[] responseBody = executeStoreRequest(
                trace(storeConfig, "request vivo endpoint " + firstString(payload, "method"), "POST", vivoBaseUrl(endpoint), null, requestLog),
                () -> restClient.post()
                        .uri(vivoBaseUrl(endpoint))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(body)
                        .retrieve()
                        .body(byte[].class)
        );
        return decodeVivoResponseBody(responseBody);
    }

    private String buildVivoCreateScreenshotValue(List<String> screenshotSerialNumbers) {
        List<String> sanitized = screenshotSerialNumbers == null
                ? List.of()
                : screenshotSerialNumbers.stream()
                .filter(StringUtils::hasText)
                .toList();
        validateVivoScreenshotCount(
                sanitized.size(),
                "Vivo app.sync.create.app requires 3 to 5 uploaded screenshot serial numbers."
        );
        return String.join(",", sanitized);
    }

    private void validateVivoScreenshotCount(int screenshotCount, String message) {
        if (screenshotCount < VIVO_MIN_SCREENSHOT_COUNT || screenshotCount > VIVO_MAX_SCREENSHOT_COUNT) {
            throw new IllegalStateException(message + " Current count=" + screenshotCount + ".");
        }
    }

    private VivoAppDetailsResult toVivoAppDetailsResult(Map<String, Object> response, String requestLog, String responseLog) {
        Map<String, Object> data = asMap(response.get("data"));
        int status = intValue(data.get("status"));
        int saleStatus = intValue(data.get("saleStatus"));
        String rejectReason = firstString(data, "unPassReason", "rejectReason", "auditOpinion");
        ReleaseStatus releaseStatus = mapVivoAppStatus(status, saleStatus, rejectReason);
        return new VivoAppDetailsResult(
                status,
                saleStatus,
                releaseStatus,
                StringUtils.hasText(rejectReason) ? rejectReason : null,
                requestLog,
                responseLog
        );
    }

    private StoreSubmitResult toStoreSubmitResult(VivoUploadResult result) {
        return new StoreSubmitResult(result.serialNumber(), result.requestLog(), result.responseLog(), "upload success");
    }

    private boolean isVivoAppNotFoundResponse(Map<String, Object> response) {
        return VIVO_SUB_CODE_APP_NOT_FOUND.equals(firstString(response, "subCode", "sub_code"));
    }

    private <T> T executeStoreRequest(String action, Supplier<T> request) {
        return executeStoreRequest(null, action, request);
    }

    private <T> T executeStoreRequest(StoreRequestTrace trace, Supplier<T> request) {
        return executeStoreRequest(trace, trace == null ? null : trace.action(), request);
    }

    private <T> T executeStoreRequest(StoreRequestTrace trace, String action, Supplier<T> request) {
        long startTime = System.currentTimeMillis();
        try {
            T response = request.get();
            logStoreRequestSuccess(trace, response, System.currentTimeMillis() - startTime);
            return response;
        } catch (StoreApiException ex) {
            logStoreRequestFailure(trace, ex.getStatus().value(), ex.getMessage(), null, System.currentTimeMillis() - startTime);
            throw ex;
        } catch (ResourceAccessException ex) {
            StoreApiException translated = translateResourceAccessException(action, ex);
            logStoreRequestFailure(trace, translated.getStatus().value(), translated.getMessage(), null, System.currentTimeMillis() - startTime);
            throw translated;
        } catch (RestClientResponseException ex) {
            String responseBody = isVivoAction(action)
                    ? decodeVivoResponseBody(ex.getResponseBodyAsByteArray())
                    : ex.getResponseBodyAsString();
            String message = "Store API request failed during " + action + ": status=" + ex.getStatusCode()
                    + buildResponseBodySuffix(responseBody);
            log.warn("Store API returned HTTP error during {}, status={}", action, ex.getStatusCode(), ex);
            logStoreRequestFailure(trace, ex.getStatusCode().value(), message, responseBody, System.currentTimeMillis() - startTime);
            throw new StoreApiException(HttpStatus.BAD_GATEWAY, message, ex);
        } catch (RestClientException ex) {
            String message = "Store API request failed during " + action + ": " + ex.getMessage();
            log.warn("Store API call failed during {}", action, ex);
            logStoreRequestFailure(trace, HttpStatus.BAD_GATEWAY.value(), message, null, System.currentTimeMillis() - startTime);
            throw new StoreApiException(HttpStatus.BAD_GATEWAY, message, ex);
        }
    }

    private StoreRequestTrace trace(
            AppStoreConfig storeConfig,
            String action,
            String requestMethod,
            String requestUrl,
            Object requestParams,
            Object requestBody
    ) {
        return new StoreRequestTrace(storeConfig, action, requestMethod, requestUrl, requestParams, requestBody);
    }

    private Map<String, Object> requestPayload(Object body, Map<String, Object> extras) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> payload.put(String.valueOf(key), value));
        } else if (body != null) {
            payload.put("body", body);
        }
        if (extras != null && !extras.isEmpty()) {
            payload.putAll(extras);
        }
        return payload;
    }

    private void logStoreRequestSuccess(StoreRequestTrace trace, Object response, long durationMs) {
        if (trace == null || storeRequestLogService == null) {
            return;
        }
        storeRequestLogService.logSuccess(
                trace.storeConfig(),
                trace.action(),
                trace.requestMethod(),
                trace.requestUrl(),
                trace.requestParams(),
                trace.requestBody(),
                HttpStatus.OK.value(),
                stringifyResponse(trace.action(), response),
                durationMs
        );
    }

    private void logStoreRequestFailure(
            StoreRequestTrace trace,
            Integer responseStatusCode,
            String errorMessage,
            Object responseBody,
            long durationMs
    ) {
        if (trace == null || storeRequestLogService == null) {
            return;
        }
        storeRequestLogService.logFailure(
                trace.storeConfig(),
                trace.action(),
                trace.requestMethod(),
                trace.requestUrl(),
                trace.requestParams(),
                trace.requestBody(),
                responseStatusCode,
                responseBody,
                errorMessage,
                durationMs
        );
    }

    private String stringifyResponse(String action, Object response) {
        if (response == null) {
            return null;
        }
        if (response instanceof byte[] bytes) {
            return isVivoAction(action) ? decodeVivoResponseBody(bytes) : new String(bytes, StandardCharsets.UTF_8);
        }
        if (response instanceof Map<?, ?> map) {
            return writeJson(map);
        }
        if (response instanceof Path path) {
            return path.toString();
        }
        return String.valueOf(response);
    }

    private StoreApiException translateResourceAccessException(String action, ResourceAccessException ex) {
        boolean timeout = isTimeoutException(ex);
        HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        String message = timeout
                ? "Store API request timed out during " + action
                : "Store API is unavailable during " + action + ": " + ex.getMessage();
        log.warn("Store API resource access failed during {}, timeout={}", action, timeout, ex);
        return new StoreApiException(status, message, ex);
    }

    private boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof InterruptedIOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("timed out") || normalized.contains("interrupted")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String buildResponseBodySuffix(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "";
        }
        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 300) {
            normalized = normalized.substring(0, 300) + "...";
        }
        return ", body=" + normalized;
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

    private ReleaseStatus mapVivoAppStatus(int status, int saleStatus, String rejectReason) {
        if (StringUtils.hasText(rejectReason)) {
            return ReleaseStatus.REJECT;
        }
        if (saleStatus == 2) {
            return ReleaseStatus.OFFLINE;
        }
        if (isVivoAppApprovedAndOnline(status, saleStatus)) {
            return ReleaseStatus.PASS;
        }
        return ReleaseStatus.AUDITING;
    }

    private boolean isVivoAppApprovedAndOnline(int status, int saleStatus) {
        return saleStatus == 1 && status == 3;
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
        return requireLocalPackage(version.getPackageUrl(), "Package path is empty");
    }

    private Path requireLocalPackage(String packageLocation, String emptyMessage) {
        if (!StringUtils.hasText(packageLocation)) {
            throw new IllegalArgumentException(emptyMessage);
        }
        String normalizedLocation = packageLocation.trim();
        Path packagePath = toPath(normalizedLocation);
        if (packagePath != null && Files.exists(packagePath) && Files.isRegularFile(packagePath)) {
            return packagePath;
        }

        String downloadUrl = resolvePackageDownloadUrl(normalizedLocation, packagePath);
        if (!StringUtils.hasText(downloadUrl)) {
            throw new IllegalArgumentException("Package file not found: " + normalizedLocation);
        }
        return downloadPackageToLocal(downloadUrl, inferFileName(normalizedLocation));
    }

    private PackageContentSource resolveVivoPackageSource(String packageLocation, String emptyMessage) {
        if (!StringUtils.hasText(packageLocation)) {
            throw new IllegalArgumentException(emptyMessage);
        }
        String normalizedLocation = packageLocation.trim();
        Path packagePath = toPath(normalizedLocation);
        if (packagePath != null && Files.exists(packagePath) && Files.isRegularFile(packagePath)) {
            return new PackageContentSource(packagePath.getFileName().toString(), packagePath, null);
        }

        String downloadUrl = resolvePackageDownloadUrl(normalizedLocation, packagePath);
        if (!StringUtils.hasText(downloadUrl)) {
            throw new IllegalArgumentException("Package file not found: " + normalizedLocation);
        }
        if (appProperties.getPackageRepository().isStreamUploadEnabled()) {
            return new PackageContentSource(inferFileName(normalizedLocation), null, downloadUrl);
        }

        Path localPath = downloadPackageToLocal(downloadUrl, inferFileName(normalizedLocation));
        return new PackageContentSource(localPath.getFileName().toString(), localPath, null);
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
        return md5Hex(new PackageContentSource(path.getFileName().toString(), path, null));
    }

    private String md5Hex(PackageContentSource packageSource) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            try (InputStream inputStream = openPackageStream(packageSource)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    messageDigest.update(buffer, 0, read);
                }
            }
            return toHex(messageDigest.digest());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read package file: " + packageSource.fileName(), ex);
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

    private boolean isVivoAction(String action) {
        return StringUtils.hasText(action) && action.toLowerCase().contains("vivo");
    }

    private String decodeVivoResponseBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String utf8 = decodeStrict(body, StandardCharsets.UTF_8);
        if (utf8 != null && !looksLikeMojibake(utf8)) {
            return utf8;
        }
        String gbk = decodeStrict(body, GBK);
        if (gbk != null && !looksLikeMojibake(gbk)) {
            return gbk;
        }
        if (utf8 != null) {
            return utf8;
        }
        if (gbk != null) {
            return gbk;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private String decodeStrict(byte[] body, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    private boolean looksLikeMojibake(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return text.contains("\uFFFD")
                || text.contains("\u00E6")
                || text.contains("\u00E5")
                || text.contains("\u00E4")
                || text.contains("\u00E7")
                || text.contains("\u00EF\u00BC");
    }

    private ProjectMetadataContext readProjectMetadata(Path packagePath) {
        Path metadataPath = findProjectMetadataPath(packagePath);
        if (metadataPath == null) {
            return new ProjectMetadataContext(null, Map.of());
        }
        try (InputStream inputStream = Files.newInputStream(metadataPath)) {
            return new ProjectMetadataContext(
                    metadataPath,
                    objectMapper.readValue(inputStream, new TypeReference<>() {
                    })
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read project metadata: " + metadataPath, ex);
        }
    }

    private ProjectMetadataContext resolveProjectMetadataContext(String packageLocation) {
        if (StringUtils.hasText(packageLocation)) {
            Path packagePath = toPath(packageLocation.trim());
            if (packagePath != null && Files.exists(packagePath)) {
                return readProjectMetadata(packagePath);
            }
        }
        return readProjectMetadata(Path.of("."));
    }

    private Path findProjectMetadataPath(Path packagePath) {
        Path current = packagePath.toAbsolutePath().normalize();
        if (!Files.isDirectory(current)) {
            current = current.getParent();
        }
        while (current != null) {
            Path candidate = current.resolve(PROJECT_METADATA_FILE_NAME);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        Path fallback = Path.of(PROJECT_METADATA_FILE_NAME).toAbsolutePath().normalize();
        if (Files.exists(fallback) && Files.isRegularFile(fallback)) {
            return fallback;
        }
        return null;
    }

    private Path resolveVivoIconPath(ProjectMetadataContext metadataContext, Map<String, Object> metadata) {
        Object metadataValue = metadataLookup(metadata, "vivo", "iconPath");
        if (metadataValue == null) {
            metadataValue = metadataLookup(metadata, null, "vivoIconPath");
        }
        return resolveProjectAssetPath(metadataContext.metadataPath(), metadataValue);
    }

    private List<Path> resolveVivoScreenshotPaths(ProjectMetadataContext metadataContext, Map<String, Object> metadata) {
        return resolveProjectAssetPaths(
                metadataContext.metadataPath(),
                firstList(metadataLookup(metadata, "vivo", "screenshotPaths"), metadataLookup(metadata, "vivo", "screenshots"), metadataLookup(metadata, null, "vivoScreenshotPaths"))
        );
    }

    private Path resolveProjectAssetPath(Path metadataPath, Object assetLocation) {
        if (assetLocation == null) {
            return null;
        }
        String location = String.valueOf(assetLocation).trim();
        if (!StringUtils.hasText(location)) {
            return null;
        }

        Path directPath = toPath(location);
        if (directPath != null) {
            if (!directPath.isAbsolute()) {
                Path parent = metadataPath == null ? null : metadataPath.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    Path projectPath = parent.resolve(directPath).normalize();
                    if (Files.exists(projectPath) && Files.isRegularFile(projectPath)) {
                        return projectPath;
                    }
                }
            } else if (Files.exists(directPath) && Files.isRegularFile(directPath)) {
                return directPath;
            }
        }
        return null;
    }

    private List<Path> resolveProjectAssetPaths(Path metadataPath, List<String> assetLocations) {
        if (assetLocations == null || assetLocations.isEmpty()) {
            return List.of();
        }
        List<Path> resolved = new ArrayList<>();
        for (String assetLocation : assetLocations) {
            Path path = resolveProjectAssetPath(metadataPath, assetLocation);
            if (path == null) {
                throw new IllegalStateException("Vivo asset file not found in project: " + assetLocation);
            }
            resolved.add(path);
        }
        return resolved;
    }

    private Object metadataLookup(Map<String, Object> metadata, String sectionKey, String key) {
        if (metadata == null || !StringUtils.hasText(key)) {
            return null;
        }
        if (StringUtils.hasText(sectionKey)) {
            Map<String, Object> section = asMap(metadata.get(sectionKey));
            if (!section.isEmpty()) {
                Object value = firstNonNull(
                        section.get(key),
                        section.get(toSnakeCase(key))
                );
                if (value != null) {
                    return value;
                }
            }
        }
        return firstNonNull(metadata.get(key), metadata.get(toSnakeCase(key)));
    }

    private List<String> firstList(Object... values) {
        if (values == null) {
            return List.of();
        }
        for (Object value : values) {
            if (value instanceof List<?> list && !list.isEmpty()) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    if (item != null && StringUtils.hasText(String.valueOf(item))) {
                        result.add(String.valueOf(item));
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return List.of();
    }

    private Integer firstInteger(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            int parsed = intValue(value);
            if (parsed >= 0) {
                return parsed;
            }
        }
        return null;
    }

    private String stripFileExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private String toSnakeCase(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private Map<String, Object> readJson(String body) {
        if (!StringUtils.hasText(body)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new StoreApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Store API returned unexpected response" + buildResponseBodySuffix(body),
                    ex
            );
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
        throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Oppo " + action + " failed: errno=" + errno + ", msg=" + message);
    }

    private void ensureVivoSuccess(Map<String, Object> response) {
        int code = intValue(response.get("code"));
        String subCode = firstString(response, "subCode", "sub_code");
        if (code == 0 && (!StringUtils.hasText(subCode) || "0".equals(subCode))) {
            return;
        }
        String message = firstString(response, "msg", "message");
        throw new StoreApiException(
                HttpStatus.BAD_GATEWAY,
                "Vivo request failed: code=" + code
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

    private void ensureVivoCreateAllowed(AppStoreConfig storeConfig) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isSandboxEnabled()) {
            return;
        }
        throw new IllegalStateException("Vivo app create is limited to sandbox environment. Set app.store-api.stores.vivo.sandbox-enabled=true before retrying.");
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
        return executeStoreRequest(
                "download package from repository",
                () -> restClient.get()
                        .uri(downloadUrl)
                        .headers(headers -> applyPackageAuthorization(headers, downloadUrl))
                        .exchange((request, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                throw new StoreApiException(
                                        HttpStatus.BAD_GATEWAY,
                                        "Failed to download package from repository: status=" + response.getStatusCode()
                                );
                            }
                            try (InputStream inputStream = response.getBody()) {
                                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                                return target;
                            } catch (IOException ex) {
                                throw new IllegalStateException("Failed to save downloaded package: " + target, ex);
                            }
                        })
        );
    }

    private InputStream openPackageStream(PackageContentSource packageSource) throws IOException {
        if (packageSource.localPath() != null) {
            return Files.newInputStream(packageSource.localPath());
        }
        return openRemotePackageStream(packageSource.remoteUrl());
    }

    private Object uploadResource(PackageContentSource packageSource) {
        if (packageSource.localPath() != null) {
            return new FileSystemResource(packageSource.localPath());
        }
        return new RemotePackageResource(packageSource.fileName(), packageSource.remoteUrl());
    }

    private InputStream openRemotePackageStream(String downloadUrl) throws IOException {
        if (!StringUtils.hasText(downloadUrl)) {
            throw new IllegalArgumentException("Remote package url is empty");
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(Math.max(appProperties.getStoreApi().getDefaultTimeoutSeconds(), 1)))
                .GET();
        String authorization = resolvePackageAuthorization(downloadUrl);
        if (StringUtils.hasText(authorization)) {
            requestBuilder.header(HttpHeaders.AUTHORIZATION, authorization);
        }

        HttpResponse<InputStream> response;
        try {
            response = packageHttpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Package stream request interrupted");
            interrupted.initCause(ex);
            throw interrupted;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) {
                // close response body before surfacing the error
            }
            throw new StoreApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to open remote package stream: status=" + response.statusCode()
            );
        }
        return response.body();
    }

    private void applyPackageAuthorization(HttpHeaders headers, String downloadUrl) {
        String authorization = resolvePackageAuthorization(downloadUrl);
        if (StringUtils.hasText(authorization)) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    private String resolvePackageAuthorization(String downloadUrl) {
        if (!StringUtils.hasText(downloadUrl)) {
            return null;
        }
        if (!isPackageRepositoryUrl(downloadUrl) && !isCmsArtifactUrl(downloadUrl)) {
            return null;
        }
        String configuredAuthorization = appProperties.getPackageRepository().getAuthorization();
        if (StringUtils.hasText(configuredAuthorization)) {
            return configuredAuthorization.trim();
        }
        return isCmsArtifactUrl(downloadUrl) ? ApkDownloadUtil.defaultAuthorizationValue() : null;
    }

    private boolean isPackageRepositoryUrl(String downloadUrl) {
        String baseUrl = appProperties.getPackageRepository().getBaseUrl();
        return StringUtils.hasText(baseUrl) && downloadUrl.startsWith(baseUrl.trim());
    }

    private boolean isCmsArtifactUrl(String downloadUrl) {
        try {
            return CMS_ARTIFACT_HOST.equalsIgnoreCase(URI.create(downloadUrl).getHost());
        } catch (IllegalArgumentException ex) {
            return false;
        }
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

    private record VivoAppDetailsResult(
            int status,
            int saleStatus,
            ReleaseStatus releaseStatus,
            String rejectReason,
            String requestLog,
            String responseLog
    ) {
    }

    private record VivoAppDetailsLookupResult(
            boolean appMissing,
            VivoAppDetailsResult appDetails,
            String requestLog,
            String responseLog
    ) {
    }

    private record VivoUploadResult(
            String serialNumber,
            String requestLog,
            String responseLog
    ) {
    }

    private record VivoPackageBundle(
            PackageContentSource apk32Source,
            PackageContentSource apk64Source,
            ProjectMetadataContext metadataContext
    ) {
    }

    private record VivoPackageUploadBundle(
            VivoUploadResult apk32UploadResult,
            VivoUploadResult apk64UploadResult
    ) {
    }

    private record ProjectMetadataContext(
            Path metadataPath,
            Map<String, Object> metadata
    ) {
    }

    private record PackageContentSource(
            String fileName,
            Path localPath,
            String remoteUrl
    ) {
        private String sourceType() {
            return localPath != null ? "local" : "remote";
        }
    }

    private record StoreRequestTrace(
            AppStoreConfig storeConfig,
            String action,
            String requestMethod,
            String requestUrl,
            Object requestParams,
            Object requestBody
    ) {
    }

    private record VivoCreateContext(
            String packageName,
            Path iconPath,
            List<Path> screenshotPaths,
            int appClassify,
            int subAppClassify,
            int compatibleDevice,
            int rateAge
    ) {
    }

    private final class RemotePackageResource extends AbstractResource {

        private final String fileName;
        private final String downloadUrl;

        private RemotePackageResource(String fileName, String downloadUrl) {
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
        }

        @Override
        public String getDescription() {
            return "remote package " + downloadUrl;
        }

        @Override
        public String getFilename() {
            return fileName;
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return openRemotePackageStream(downloadUrl);
        }
    }
}
