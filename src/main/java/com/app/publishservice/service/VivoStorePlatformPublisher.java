package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.common.exception.StoreApiException;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppInfo;
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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class VivoStorePlatformPublisher extends AbstractStorePlatformPublisher implements StorePlatformPublisher {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String VIVO_PRODUCTION_BASE_URL = "https://developer-api.vivo.com.cn/router/rest";
    private static final String VIVO_SANDBOX_BASE_URL = "https://sandbox-developer-api.vivo.com.cn/router/rest";
    private static final String VIVO_METHOD_UPLOAD_APK_32 = "app.upload.apk.app.32";
    private static final String VIVO_METHOD_UPLOAD_APK_64 = "app.upload.apk.app.64";
    private static final String VIVO_METHOD_UPLOAD_ICON = "app.upload.icon";
    private static final String VIVO_METHOD_UPLOAD_SCREENSHOT = "app.upload.screenshot";
    private static final String VIVO_METHOD_CREATE_SUBPACKAGE_APP = "app.sync.create.subpackage.app";
    private static final String VIVO_METHOD_UPDATE_SUBPACKAGE_APP = "app.sync.update.subpackage.app";
    private static final String VIVO_METHOD_CREATE_UPDATE_STAGE = "app.sync.create.update.stage.app";
    private static final String VIVO_METHOD_QUERY_APP_DETAILS = "app.query.details";
    private static final String VIVO_METHOD_QUERY_STAGE_DETAILS = "app.query.stage.details";
    private static final String VIVO_SUB_CODE_APP_NOT_FOUND = "11001";
    private static final int VIVO_DEFAULT_COMPATIBLE_DEVICE = 2;
    private static final int VIVO_DEFAULT_RATE_AGE = 12;
    private static final int VIVO_MIN_SCREENSHOT_COUNT = 3;
    private static final int VIVO_MAX_SCREENSHOT_COUNT = 5;
    private static final String VIVO_TARGET_APP_KEY = "developer";
    private static final String VIVO_FORMAT = "json";
    private static final String VIVO_SIGN_METHOD = "hmac";
    private static final String VIVO_PROTOCOL_VERSION = "1.0";
    private static final DateTimeFormatter VIVO_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 初始化VivoStorePlatformPublisher。
     */
    VivoStorePlatformPublisher(
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
                && "vivo".equalsIgnoreCase(storeConfig.getStoreType().getCode());
    }

    /**
     * 刷新令牌。
     */
    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        String marker = StringUtils.hasText(storeConfig.getClientId()) ? storeConfig.getClientId() : "vivo-static";
        return new TokenPayload(TokenType.STATIC.getCode(), marker, LocalDateTime.now().plusYears(10));
    }

    /**
     * 提交发布。
     */
    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return submitVivoRelease(storeConfig, version, record);
        }
    }

    /**
     * 查询审核。
     */
    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return isStagedRelease(record)
                    ? queryVivoStageDetails(storeConfig, record)
                    : queryVivoAppDetails(storeConfig, record);
        }
    }

    /**
     * 提交VIVO 发布。
     */
    protected StoreSubmitResult submitVivoRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record) {
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

    /**
     * 查询VIVO 阶段详情。
     */
    protected StoreReviewResult queryVivoStageDetails(AppStoreConfig storeConfig, AppReleaseRecord record) {
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

    /**
     * 查询VIVO 应用详情。
     */
    protected StoreReviewResult queryVivoAppDetails(AppStoreConfig storeConfig, AppReleaseRecord record) {
        VivoAppDetailsResult appDetails = queryVivoAppDetails(storeConfig, record.getPackageName());
        return new StoreReviewResult(appDetails.releaseStatus(), appDetails.responseLog(), appDetails.rejectReason());
    }

    /**
     * 处理VIVO Base URL相关逻辑。
     */
    protected String vivoBaseUrl(StoreApiProperties.StoreEndpointProperties endpoint) {
        if (StringUtils.hasText(endpoint.getBaseUrl())) {
            return endpoint.getBaseUrl();
        }
        return endpoint.isSandboxEnabled() ? VIVO_SANDBOX_BASE_URL : VIVO_PRODUCTION_BASE_URL;
    }

    /**
     * 解码商店响应报文。
     */
    @Override
    protected String decodeStoreResponseBody(String action, byte[] body) {
        return decodeVivoResponseBody(body);
    }

    /**
     * 提交VIVO Full 发布。
     */
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

    /**
     * 提交VIVO 阶段发布。
     */
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

    /**
     * 确保VIVO 提交 Ready。
     */
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

    /**
     * 提交VIVO Create 应用。
     */
    private StoreSubmitResult submitVivoCreateApp(
            AppStoreConfig storeConfig,
            AppVersion version,
            VivoAppDetailsLookupResult appDetailsLookup
    ) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        VivoPackageBundle packageBundle = resolveVivoPackageBundle(version);
        VivoCreateContext createContext = resolveVivoCreateContext(version, packageBundle, endpoint);
        VivoPackageUploadBundle uploadBundle = uploadVivoPackageBundle(storeConfig, version, packageBundle, false);

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

    /**
     * 上传VIVO 包 Bundle。
     */
    private VivoPackageUploadBundle uploadVivoPackageBundle(AppStoreConfig storeConfig, AppVersion version, boolean stageUpload) {
        return uploadVivoPackageBundle(storeConfig, version, resolveVivoPackageBundle(version), stageUpload);
    }

    /**
     * 上传VIVO 包 Bundle。
     */
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

    /**
     * 提交VIVO APK 上传。
     */
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
                        .body(byte[].class),
                () -> mockVivoUploadResponseBytes("vivo-" + UUID.randomUUID(), "mock upload success")
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

    /**
     * 查询VIVO 应用详情。
     */
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
        String responseBody = postVivoForm(storeConfig, endpoint, payload, sign);
        Map<String, Object> response = readJson(responseBody);
        ensureVivoSuccess(response);
        return toVivoAppDetailsResult(response, writeJson(requestLog), responseBody);
    }

    /**
     * 查询VIVO 应用详情提交。
     */
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

    /**
     * 构建VIVO 上传载荷。
     */
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

    /**
     * 构建VIVO Subpackage Create 载荷。
     */
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
        payload.put("updateDesc", normalizeStageText(5, 200,  appDescription, appName, packageName, "Version update notes"));
        payload.put("detailDesc", normalizeStageText(50, 1000, appDescription, version.getUpdateLog(), appName, packageName, "App description"));
        payload.put("icon", iconSerialNumber);
        payload.put("screenshot", screenshotValue);
        payload.put("appClassify", createContext.appClassify());
        payload.put("subAppClassify", createContext.subAppClassify());
        payload.put("compatibleDevice", createContext.compatibleDevice());
        payload.put("rateAge", createContext.rateAge());
        return payload;
    }

    /**
     * 构建VIVO Subpackage Update 载荷。
     */
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
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isSandboxEnabled()) {
            payload.put("versionCode", version.getVersionCode().replace(".", ""));
        }
        payload.put("apk32", apk32SerialNumber);
        payload.put("apk64", apk64SerialNumber);
        payload.put("onlineType", 1);
        payload.put("updateDesc", normalizeStageText(5, 200,  appDescription, appName, packageName, "Version update notes"));
        //payload.put("detailDesc", normalizeStageText(50, 1000, appDescription, version.getUpdateLog(), appName, packageName, "App description"));
        //payload.put("mainTitle", normalizeTitle(appName, packageName));
        //payload.put("subTitle", "");
        //payload.put("remark", normalizeStageText(10, 200, version.getUpdateLog(), appDescription, appName, packageName, "App update review notes"));
        payload.put("compatibleDevice", VIVO_DEFAULT_COMPATIBLE_DEVICE);
        return payload;
    }

    /**
     * 构建VIVO Subpackage 阶段载荷。
     */
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
        payload.put("updateDesc", normalizeStageText(5, 200,  appDescription, appName, packageName, "Version update notes"));
        //payload.put("simpleDesc", normalizeStageText(10, 500, appDescription, version.getUpdateLog(), appName, packageName, "App release notes"));
        //payload.put("description", normalizeStageText(20, 1000, appDescription, version.getUpdateLog(), appName, packageName, "App detail description"));
        //payload.put("remark", normalizeStageText(5, 500, version.getUpdateLog(), appDescription, appName, packageName, "Stage release notes"));
        //payload.put("mainTitle", normalizeTitle(appName, packageName));
        //payload.put("subTitle", "");
        return payload;
    }

    /**
     * 构建VIVO Common 载荷。
     */
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

    /**
     * 上传VIVO Asset。
     */
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
                        .body(byte[].class),
                () -> mockVivoUploadResponseBytes(assetType + "-" + UUID.randomUUID(), "mock " + assetType + " upload success")
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

    /**
     * 解析VIVO 包 Bundle。
     */
    private VivoPackageBundle resolveVivoPackageBundle(AppVersion version) {
        String defaultApk64Location = firstNonBlank(version.getPackageUrl64(), version.getPackageUrl());
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
        if (apk32Source == null && StringUtils.hasText(version.getPackageUrl32())) {
            apk32Source = resolveVivoPackageSource(version.getPackageUrl32(), "32-bit package path is empty");
        }
        PackageContentSource apk64Source = apk64Path == null
                ? resolveVivoPackageSource(defaultApk64Location, "64-bit package path is empty")
                : new PackageContentSource(apk64Path.getFileName().toString(), apk64Path, null);
        if (apk32Source == null) {
            String defaultFileName = StringUtils.hasText(defaultApk64Location) ? inferFileName(defaultApk64Location) : null;
            if (StringUtils.hasText(defaultFileName) && !defaultFileName.toLowerCase().endsWith(".apk")) {
                throw new IllegalArgumentException("Vivo upload only supports APK packages");
            }
            throw new IllegalStateException("Vivo subpackage submit requires 32-bit apk. Provide app.publish-metadata.values.vivo.apk32Path in application.yml.");
        }
        return new VivoPackageBundle(apk32Source, apk64Source, metadataContext);
    }

    /**
     * 解析VIVO Create 上下文。
     */
    private VivoCreateContext resolveVivoCreateContext(AppVersion version, ProjectMetadataContext metadataContext) {
        AppInfo appInfo = version.getAppInfo();
        if (appInfo == null || !StringUtils.hasText(appInfo.getPackageName())) {
            throw new IllegalArgumentException("Vivo create app requires app packageName");
        }

        Map<String, Object> metadata = metadataContext.metadata();
        Path iconPath = resolveVivoIconPath(metadataContext, metadata);
        List<Path> screenshotPaths = resolveVivoScreenshotPaths(metadataContext, metadata);
        if (iconPath == null) {
            throw new IllegalStateException("Vivo app create requires icon asset. Provide app.publish-metadata.values.vivo.iconPath in application.yml.");
        }
        validateVivoScreenshotCount(screenshotPaths.size(), "Vivo app create requires 3 to 5 screenshots. Configure app.publish-metadata.values.vivo.screenshotPaths in application.yml.");

        Integer appClassify = firstInteger(metadataLookup(metadata, "vivo", "appClassify"), metadataLookup(metadata, null, "appClassify"));
        Integer subAppClassify = firstInteger(metadataLookup(metadata, "vivo", "subAppClassify"), metadataLookup(metadata, null, "subAppClassify"));
        if (appClassify == null || subAppClassify == null) {
            throw new IllegalStateException("Vivo app create requires app.publish-metadata.values.vivo.appClassify and app.publish-metadata.values.vivo.subAppClassify in application.yml.");
        }
        Integer compatibleDevice = firstInteger(metadataLookup(metadata, "vivo", "compatibleDevice"), VIVO_DEFAULT_COMPATIBLE_DEVICE);
        Integer rateAge = firstInteger(metadataLookup(metadata, "vivo", "rateAge"), VIVO_DEFAULT_RATE_AGE);
        return new VivoCreateContext(
                appInfo.getPackageName(),
                iconPath,
                screenshotPaths,
                appClassify,
                subAppClassify,
                compatibleDevice == null ? VIVO_DEFAULT_COMPATIBLE_DEVICE : compatibleDevice,
                rateAge == null ? VIVO_DEFAULT_RATE_AGE : rateAge
        );
    }

    private VivoCreateContext resolveVivoCreateContext(
            AppVersion version,
            VivoPackageBundle packageBundle,
            StoreApiProperties.StoreEndpointProperties endpoint
    ) {
        return endpoint.isMockEnabled()
                ? mockVivoCreateContext(version, packageBundle)
                : resolveVivoCreateContext(version, packageBundle.metadataContext());
    }

    private VivoCreateContext mockVivoCreateContext(AppVersion version, VivoPackageBundle packageBundle) {
        AppInfo appInfo = version.getAppInfo();
        if (appInfo == null || !StringUtils.hasText(appInfo.getPackageName())) {
            throw new IllegalArgumentException("Vivo create app requires app packageName");
        }

        Path mockAssetPath = packageBundle.apk64Source().localPath();
        if (mockAssetPath == null && packageBundle.apk32Source() != null) {
            mockAssetPath = packageBundle.apk32Source().localPath();
        }
        if (mockAssetPath == null) {
            mockAssetPath = requireLocalPackage(
                    firstNonBlank(version.getPackageUrl64(), version.getPackageUrl32(), version.getPackageUrl()),
                    "Vivo mock create requires local package path"
            );
        }

        return new VivoCreateContext(
                appInfo.getPackageName(),
                mockAssetPath,
                List.of(mockAssetPath, mockAssetPath, mockAssetPath),
                1,
                1,
                VIVO_DEFAULT_COMPATIBLE_DEVICE,
                VIVO_DEFAULT_RATE_AGE
        );
    }

    /**
     * 处理wait VIVO 包 Parsing相关逻辑。
     */
    private void waitForVivoPackageParsing(StoreApiProperties.StoreEndpointProperties endpoint) {
        if (endpoint.isMockEnabled()) {
            return;
        }
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

    /**
     * 发送VIVO 表单。
     */
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
                        .body(byte[].class),
                () -> mockVivoMethodResponseBytes(payload)
        );
        return decodeVivoResponseBody(responseBody);
    }

    /**
     * 构建VIVO Create 截图值。
     */
    private String buildVivoCreateScreenshotValue(List<String> screenshotSerialNumbers) {
        List<String> sanitized = screenshotSerialNumbers == null
                ? List.of()
                : screenshotSerialNumbers.stream().filter(StringUtils::hasText).toList();
        validateVivoScreenshotCount(
                sanitized.size(),
                "Vivo app.sync.create.app requires 3 to 5 uploaded screenshot serial numbers."
        );
        return String.join(",", sanitized);
    }

    /**
     * 校验VIVO 截图 Count。
     */
    private void validateVivoScreenshotCount(int screenshotCount, String message) {
        if (screenshotCount < VIVO_MIN_SCREENSHOT_COUNT || screenshotCount > VIVO_MAX_SCREENSHOT_COUNT) {
            throw new IllegalStateException(message + " Current count=" + screenshotCount + ".");
        }
    }

    /**
     * 处理VIVO 应用详情结果相关逻辑。
     */
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

    /**
     * 判断是否VIVO 应用 Not Found 响应。
     */
    private boolean isVivoAppNotFoundResponse(Map<String, Object> response) {
        return VIVO_SUB_CODE_APP_NOT_FOUND.equals(firstString(response, "subCode", "sub_code"));
    }

    /**
     * 映射VIVO 阶段状态。
     */
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

    /**
     * 映射VIVO 应用状态。
     */
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

    /**
     * 判断是否VIVO 应用 Approved Online。
     */
    private boolean isVivoAppApprovedAndOnline(int status, int saleStatus) {
        return saleStatus == 1 && status == 3;
    }

    /**
     * 确保VIVO Create Allowed。
     */
    private void ensureVivoCreateAllowed(AppStoreConfig storeConfig) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled() || endpoint.isSandboxEnabled()) {
            return;
        }
        throw new IllegalStateException("Vivo app create is limited to sandbox environment. Set app.store-api.stores.vivo.sandbox-enabled=true before retrying.");
    }

    /**
     * 解析VIVO 包 Source。
     */
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

    /**
     * 签名VIVO 载荷。
     */
    private String signVivoPayload(Map<String, Object> payload, String accessSecret) {
        if (!StringUtils.hasText(accessSecret)) {
            return "mock-vivo-sign";
        }
        String source = payload.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        return hmacSha256Hex(source, accessSecret);
    }

    private byte[] mockVivoMethodResponseBytes(Map<String, Object> payload) {
        String method = firstString(payload, "method");
        Map<String, Object> data = new LinkedHashMap<>();
        String message = "mock success";
        if (VIVO_METHOD_QUERY_APP_DETAILS.equals(method)) {
            data.put("packageName", firstString(payload, "packageName"));
            data.put("status", 3);
            data.put("saleStatus", 1);
        } else if (VIVO_METHOD_QUERY_STAGE_DETAILS.equals(method)) {
            data.put("packageName", firstString(payload, "packageName"));
            data.put("auditStatus", 2);
            data.put("effectStatus", 1);
        }
        if (VIVO_METHOD_UPDATE_SUBPACKAGE_APP.equals(method)) {
            message = "mock app submit success";
        } else if (VIVO_METHOD_CREATE_UPDATE_STAGE.equals(method)) {
            message = "mock stage submit success";
        } else if (VIVO_METHOD_CREATE_SUBPACKAGE_APP.equals(method)) {
            message = "mock app create success";
        }
        return mockVivoResponseBytes(message, data);
    }

    private byte[] mockVivoUploadResponseBytes(String serialNumber, String message) {
        return mockVivoResponseBytes(message, Map.of("serialnumber", serialNumber));
    }

    private byte[] mockVivoResponseBytes(String message, Map<String, Object> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 0);
        response.put("subCode", "0");
        response.put("msg", message);
        if (data != null && !data.isEmpty()) {
            response.put("data", data);
        }
        return writeJson(response).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 解析VIVO 图标路径。
     */
    private Path resolveVivoIconPath(ProjectMetadataContext metadataContext, Map<String, Object> metadata) {
        Object metadataValue = metadataLookup(metadata, "vivo", "iconPath");
        if (metadataValue == null) {
            metadataValue = metadataLookup(metadata, null, "vivoIconPath");
        }
        return resolveProjectAssetPath(metadataContext.metadataPath(), metadataValue);
    }

    /**
     * 解析VIVO 截图路径。
     */
    private List<Path> resolveVivoScreenshotPaths(ProjectMetadataContext metadataContext, Map<String, Object> metadata) {
        return resolveProjectAssetPaths(
                metadataContext.metadataPath(),
                firstList(metadataLookup(metadata, "vivo", "screenshotPaths"), metadataLookup(metadata, "vivo", "screenshots"), metadataLookup(metadata, null, "vivoScreenshotPaths")),
                "Vivo asset file not found in project: "
        );
    }

    /**
     * 确保VIVO Success。
     */
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

    /**
     * 解码VIVO 响应报文。
     */
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

    /**
     * 解码Strict。
     */
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

    /**
     * 判断Like Mojibake。
     */
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

    /**
     * 格式化VIVO Date 时间。
     */
    private String formatVivoDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return VIVO_DATE_TIME_FORMATTER.format(dateTime);
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
}
