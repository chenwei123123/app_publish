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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class SanxingStorePlatformPublisher extends AbstractStorePlatformPublisher implements StorePlatformPublisher {

    private static final String SANXING_BASE_URL = "https://devapi.samsungapps.com";
    private static final String SANXING_UPLOAD_URL = "https://seller.samsungapps.com/galaxyapi/fileUpload";
    private static final String SANXING_TOKEN_ENDPOINT = "/auth/accessToken";
    private static final String SANXING_CONTENT_INFO_ENDPOINT = "/seller/contentInfo";
    private static final String SANXING_CREATE_UPLOAD_SESSION_ENDPOINT = "/seller/createUploadSessionId";
    private static final String SANXING_ADD_BINARY_ENDPOINT = "/seller/v2/content/binary";
    private static final String SANXING_CONTENT_UPDATE_ENDPOINT = "/seller/contentUpdate";
    private static final String SANXING_STAGED_ROLLOUT_ENDPOINT = "/seller/v2/content/stagedRolloutRate";
    private static final String SANXING_CONTENT_SUBMIT_ENDPOINT = "/seller/contentSubmit";

    /**
     * 初始化SanxingStorePlatformPublisher。
     */
    SanxingStorePlatformPublisher(
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
                && "sanxing".equalsIgnoreCase(storeConfig.getStoreType().getCode());
    }

    /**
     * 刷新令牌。
     */
    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            return new TokenPayload(
                    TokenType.ACCESS_TOKEN.getCode(),
                    "mock-sanxing-" + UUID.randomUUID(),
                    LocalDateTime.now().plusMinutes(55)
            );
        }

        String serviceAccountId = resolveSanxingServiceAccountId(storeConfig);
        String jwt = createSanxingJwt(storeConfig, serviceAccountId);
        String tokenUrl = sanxingBaseUrl(endpoint) + sanxingTokenEndpoint(endpoint);
        String responseBody;
        if (sanxingOauthTokenMode(endpoint)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            body.put("assertion", jwt);
            responseBody = executeStoreRequest(
                    trace(storeConfig, "refresh sanxing token", "POST", tokenUrl, null, body),
                    () -> restClient.post()
                            .uri(tokenUrl)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(String.class)
            );
        } else {
            responseBody = executeStoreRequest(
                    trace(
                            storeConfig,
                            "refresh sanxing token",
                            "POST",
                            tokenUrl,
                            null,
                            Map.of("Authorization", "Bearer <jwt>")
                    ),
                    () -> restClient.post()
                            .uri(tokenUrl)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .body(String.class)
            );
        }

        Map<String, Object> response = readJson(responseBody);
        ensureSanxingSuccess(response, "refresh token");
        Map<String, Object> createdItem = asMap(response.get("createdItem"));
        String accessToken = firstNonBlank(
                firstString(createdItem, "accessToken", "access_token", "token"),
                firstString(response, "accessToken", "access_token", "token")
        );
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalStateException("Sanxing token response does not contain accessToken");
        }
        return new TokenPayload(TokenType.ACCESS_TOKEN.getCode(), accessToken, LocalDateTime.now().plusMinutes(55));
    }

    /**
     * 提交发布。
     */
    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return submitSanxingRelease(storeConfig, version, record, token);
        }
    }

    /**
     * 查询审核。
     */
    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return querySanxingReview(storeConfig, record, token);
        }
    }

    /**
     * 提交三星发布。
     */
    private StoreSubmitResult submitSanxingRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        SanxingContext context = resolveSanxingContext(version, record);
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            Map<String, Object> requestLog = new LinkedHashMap<>();
            requestLog.put("contentId", context.contentId());
            requestLog.put("gms", context.gms());
            requestLog.put("releaseType", record == null ? null : record.getReleaseType());
            Map<String, Object> responseLog = new LinkedHashMap<>();
            responseLog.put("contentInfo", List.of(Map.of(
                    "contentId", context.contentId(),
                    "contentStatus", "FOR_SALE",
                    "binaryList", List.of(Map.of("versionCode", version.getVersionCode()))
            )));
            responseLog.put("submit", Map.of("status", 204));
            return new StoreSubmitResult(context.contentId(), writeJson(requestLog), writeJson(responseLog), "mock submit success");
        }

        List<Map<String, Object>> contentInfoList = querySanxingContentInfo(storeConfig, token, context.contentId());
        Map<String, Object> currentContentInfo = firstSanxingContentInfo(contentInfoList, context.contentId());
        if (currentContentInfo.isEmpty()) {
            throw new IllegalStateException("Sanxing contentInfo does not contain contentId: " + context.contentId());
        }

        Map<String, Object> uploadSession = createSanxingUploadSession(storeConfig, token);
        Map<String, Object> uploadResult = uploadSanxingFile(storeConfig, token, context.packagePath(), uploadSession);
        String fileKey = firstString(uploadResult, "fileKey", "filekey");
        if (!StringUtils.hasText(fileKey)) {
            throw new IllegalStateException("Sanxing fileUpload response does not contain fileKey");
        }

        Map<String, Object> addBinaryPayload = buildSanxingAddBinaryPayload(context, currentContentInfo, fileKey);
        Map<String, Object> addBinaryResponse = sanxingJsonRequest(
                storeConfig,
                token,
                "POST",
                sanxingBaseUrl(endpoint) + SANXING_ADD_BINARY_ENDPOINT,
                addBinaryPayload,
                "add sanxing binary"
        );

        Map<String, Object> contentUpdatePayload = buildSanxingContentUpdatePayload(version, context, currentContentInfo);
        Map<String, Object> contentUpdateResponse = sanxingJsonRequest(
                storeConfig,
                token,
                "POST",
                sanxingBaseUrl(endpoint) + SANXING_CONTENT_UPDATE_ENDPOINT,
                contentUpdatePayload,
                "update sanxing content"
        );

        Map<String, Object> stagedRolloutPayload = null;
        Map<String, Object> stagedRolloutResponse = null;
        if (record != null && isStagedRelease(record)) {
            stagedRolloutPayload = buildSanxingStagedRolloutPayload(context.contentId(), record);
            stagedRolloutResponse = sanxingJsonRequest(
                    storeConfig,
                    token,
                    "PUT",
                    sanxingBaseUrl(endpoint) + SANXING_STAGED_ROLLOUT_ENDPOINT,
                    stagedRolloutPayload,
                    "update sanxing staged rollout"
            );
        }

        submitSanxingContent(storeConfig, token, context.contentId());

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("contentInfo", Map.of("contentId", context.contentId()));
        requestLog.put("createUploadSession", Map.of());
        requestLog.put("fileUpload", Map.of(
                "sessionId", firstString(uploadSession, "sessionId"),
                "fileName", context.packagePath().getFileName().toString()
        ));
        requestLog.put("addBinary", addBinaryPayload);
        requestLog.put("contentUpdate", contentUpdatePayload);
        if (stagedRolloutPayload != null) {
            requestLog.put("stagedRollout", stagedRolloutPayload);
        }

        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("contentInfo", contentInfoList);
        responseLog.put("createUploadSession", uploadSession);
        responseLog.put("fileUpload", uploadResult);
        responseLog.put("addBinary", addBinaryResponse);
        responseLog.put("contentUpdate", contentUpdateResponse);
        if (stagedRolloutResponse != null) {
            responseLog.put("stagedRollout", stagedRolloutResponse);
        }
        responseLog.put("submit", Map.of("status", 204));

        return new StoreSubmitResult(context.contentId(), writeJson(requestLog), writeJson(responseLog), "submit success");
    }

    /**
     * 查询三星审核。
     */
    private StoreReviewResult querySanxingReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            ReleaseStatus status = record.getReleaseTime() != null
                    && record.getReleaseTime().isBefore(LocalDateTime.now().minusSeconds(appProperties.getReviewAutoPassSeconds()))
                    ? ReleaseStatus.PASS
                    : ReleaseStatus.AUDITING;
            return new StoreReviewResult(status, "{\"mockStatus\":\"" + status.getCode() + "\"}", null);
        }

        String contentId = resolveSanxingContentId(record, null);
        List<Map<String, Object>> contentInfoList = querySanxingContentInfo(storeConfig, token, contentId);
        Map<String, Object> contentInfo = firstSanxingContentInfo(contentInfoList, contentId);
        if (contentInfo.isEmpty()) {
            throw new IllegalStateException("Sanxing review query did not find contentId: " + contentId);
        }

        String rawStatus = firstNonBlank(
                firstString(contentInfo, "contentStatus"),
                firstString(contentInfo, "appStatus"),
                firstString(contentInfo, "status")
        );
        ReleaseStatus releaseStatus = mapSanxingStatus(rawStatus, contentInfo, record);
        String rejectReason = releaseStatus == ReleaseStatus.REJECT
                ? firstNonBlank(
                firstString(contentInfo, "reviewComment"),
                firstString(contentInfo, "reviewMessage"),
                firstString(contentInfo, "message")
        )
                : null;

        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("contentInfo", contentInfo);
        responseLog.put("mappedStatus", releaseStatus.getCode());
        responseLog.put("matchedTargetVersion", sanxingBinaryMatchesTargetVersion(contentInfo, record));
        if (StringUtils.hasText(rejectReason)) {
            responseLog.put("rejectReason", rejectReason);
        }
        return new StoreReviewResult(releaseStatus, writeJson(responseLog), StringUtils.hasText(rejectReason) ? rejectReason : null);
    }

    /**
     * 查询三星内容 Info。
     */
    private List<Map<String, Object>> querySanxingContentInfo(AppStoreConfig storeConfig, String token, String contentId) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        Map<String, Object> queryParams = Map.of("contentId", contentId);
        String url = sanxingBaseUrl(endpoint) + sanxingContentInfoEndpoint(endpoint);
        String responseBody = executeStoreRequest(
                trace(storeConfig, "query sanxing content info", "GET", url, queryParams, Map.of("service-account-id", resolveSanxingServiceAccountId(storeConfig))),
                () -> restClient.get()
                        .uri(url + "?" + buildQueryString(queryParams))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("service-account-id", resolveSanxingServiceAccountId(storeConfig))
                        .retrieve()
                        .body(String.class)
        );
        return readSanxingContentInfo(responseBody);
    }

    /**
     * 创建三星上传 Session。
     */
    private Map<String, Object> createSanxingUploadSession(AppStoreConfig storeConfig, String token) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        String url = sanxingBaseUrl(endpoint) + SANXING_CREATE_UPLOAD_SESSION_ENDPOINT;
        String responseBody = executeStoreRequest(
                trace(storeConfig, "create sanxing upload session", "POST", url, null, Map.of("service-account-id", resolveSanxingServiceAccountId(storeConfig))),
                () -> restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("service-account-id", resolveSanxingServiceAccountId(storeConfig))
                        .contentType(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        ensureSanxingSuccess(response, "create upload session");
        if (!StringUtils.hasText(firstString(response, "sessionId"))) {
            throw new IllegalStateException("Sanxing createUploadSessionId response does not contain sessionId");
        }
        return response;
    }

    /**
     * 上传三星文件。
     */
    private Map<String, Object> uploadSanxingFile(
            AppStoreConfig storeConfig,
            String token,
            Path filePath,
            Map<String, Object> uploadSession
    ) {
        String sessionId = firstString(uploadSession, "sessionId");
        String uploadUrl = firstNonBlank(firstString(uploadSession, "url"), SANXING_UPLOAD_URL);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("sessionId", sessionId);
        body.add("file", buildSanxingFilePart(filePath));
        String responseBody = executeStoreRequest(
                trace(
                        storeConfig,
                        "upload sanxing file",
                        "POST",
                        uploadUrl,
                        null,
                        Map.of("sessionId", sessionId, "fileName", filePath.getFileName().toString())
                ),
                () -> restClient.post()
                        .uri(uploadUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("service-account-id", resolveSanxingServiceAccountId(storeConfig))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        ensureSanxingSuccess(response, "file upload");
        return response;
    }

    /**
     * 提交三星内容。
     */
    private void submitSanxingContent(AppStoreConfig storeConfig, String token, String contentId) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        String url = sanxingBaseUrl(endpoint) + sanxingSubmitEndpoint(endpoint);
        Map<String, Object> payload = Map.of("contentId", contentId);
        executeStoreRequest(
                trace(storeConfig, "submit sanxing content", "POST", url, null, payload),
                () -> restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("service-account-id", resolveSanxingServiceAccountId(storeConfig))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity()
        );
    }

    /**
     * 处理三星 JSON 请求相关逻辑。
     */
    private Map<String, Object> sanxingJsonRequest(
            AppStoreConfig storeConfig,
            String token,
            String method,
            String url,
            Map<String, Object> payload,
            String action
    ) {
        String responseBody = executeStoreRequest(
                trace(storeConfig, action, method, url, null, payload),
                () -> {
                    RestClient.RequestBodySpec requestSpec;
                    if ("PUT".equalsIgnoreCase(method)) {
                        requestSpec = restClient.put().uri(url);
                    } else {
                        requestSpec = restClient.post().uri(url);
                    }
                    return requestSpec
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("service-account-id", resolveSanxingServiceAccountId(storeConfig))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(payload)
                            .retrieve()
                            .body(String.class);
                }
        );
        Map<String, Object> response = readJson(responseBody);
        ensureSanxingSuccess(response, action);
        return response;
    }

    /**
     * 构建三星 Add Binary 载荷。
     */
    private Map<String, Object> buildSanxingAddBinaryPayload(
            SanxingContext context,
            Map<String, Object> contentInfo,
            String fileKey
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentId", context.contentId());
        payload.put("filekey", fileKey);
        payload.put("gms", context.gms());
        String binarySeqForDeviceInfo = firstNonBlank(
                context.binarySeqForDeviceInfo(),
                resolveFirstBinarySeq(contentInfo)
        );
        if (StringUtils.hasText(binarySeqForDeviceInfo)) {
            payload.put("binarySeqForDeviceInfo", binarySeqForDeviceInfo);
        }
        return payload;
    }

    /**
     * 构建三星内容 Update 载荷。
     */
    private Map<String, Object> buildSanxingContentUpdatePayload(
            AppVersion version,
            SanxingContext context,
            Map<String, Object> contentInfo
    ) {
        AppInfo appInfo = version.getAppInfo();
        Map<String, Object> metadata = context.metadata();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentId", context.contentId());
        payload.put("appTitle", requireSanxingText(
                firstNonBlank(appInfo == null ? null : appInfo.getAppName(), firstString(contentInfo, "appTitle")),
                "Sanxing contentUpdate requires appTitle"
        ));
        payload.put("contentStatus", "REGISTERING");
        payload.put("defaultLanguageCode", requireSanxingText(firstString(contentInfo, "defaultLanguageCode"), "Sanxing contentUpdate requires defaultLanguageCode"));
        payload.put("applicationType", firstNonBlank(firstString(contentInfo, "applicationType"), "android"));
        payload.put("longDescription", firstNonBlank(
                appInfo == null ? null : appInfo.getAppDescription(),
                firstString(contentInfo, "longDescription")
        ));
        payload.put("shortDescription", firstString(contentInfo, "shortDescription"));
        payload.put("newFeature", firstNonBlank(
                stringValue(sanxingMetadata(metadata, "newFeature")),
                version.getUpdateLog(),
                firstString(contentInfo, "newFeature")
        ));
        putIfHasText(payload, "ageLimit", firstString(contentInfo, "ageLimit"));
        putIfHasText(payload, "chinaAgeLimit", firstString(contentInfo, "chinaAgeLimit"));
        putIfHasText(payload, "openSourceURL", firstString(contentInfo, "openSourceURL"));

        String privacyUrl = firstNonBlank(
                stringValue(sanxingMetadata(metadata, "privatePolicyURL")),
                appInfo == null ? null : appInfo.getPrivacyUrl(),
                firstString(contentInfo, "privatePolicyURL")
        );
        payload.put("privatePolicyURLYN", StringUtils.hasText(privacyUrl) ? "Y" : "N");
        payload.put("privatePolicyURL", StringUtils.hasText(privacyUrl) ? privacyUrl.trim() : "");

        putIfHasText(payload, "youTubeURL", firstString(contentInfo, "youTubeURL"));
        putIfHasText(payload, "copyrightHolder", firstString(contentInfo, "copyrightHolder"));
        putIfHasText(payload, "supportEMail", firstString(contentInfo, "supportEMail"));
        putIfHasText(payload, "supportedSiteUrl", firstString(contentInfo, "supportedSiteUrl"));
        putIfHasText(payload, "standardPrice", firstString(contentInfo, "standardPrice"));
        payload.put("paid", firstNonBlank(firstString(contentInfo, "paid"), "N"));
        payload.put("publicationType", firstNonBlank(
                stringValue(sanxingMetadata(metadata, "publicationType")),
                firstString(contentInfo, "publicationType"),
                "01"
        ));
        putIfPresent(payload, "startPublicationDate", contentInfo.get("startPublicationDate"));
        putIfPresent(payload, "stopPublicationDate", contentInfo.get("stopPublicationDate"));
        if (contentInfo.containsKey("usExportLaws")) {
            payload.put("usExportLaws", contentInfo.get("usExportLaws"));
        }
        putIfHasText(payload, "reviewComment", firstNonBlank(
                stringValue(sanxingMetadata(metadata, "reviewComment")),
                version.getUpdateLog(),
                firstString(contentInfo, "reviewComment")
        ));
        putIfPresent(payload, "reviewFilename", contentInfo.get("reviewFilename"));
        putIfPresent(payload, "reviewFilekey", contentInfo.get("reviewFilekey"));
        putIfPresent(payload, "iconKey", contentInfo.get("iconKey"));
        putIfPresent(payload, "heroImageKey", contentInfo.get("heroImageKey"));
        putIfPresent(payload, "supportedLanguages", contentInfo.get("supportedLanguages"));

        // Keep existing locale, screenshot, and sale country settings unchanged.
        payload.put("addLanguage", "null");
        payload.put("screenshots", "null");
        payload.put("sellCountryList", "null");
        return payload;
    }

    /**
     * 构建三星灰度 Rollout 载荷。
     */
    private Map<String, Object> buildSanxingStagedRolloutPayload(String contentId, AppReleaseRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentId", contentId);
        payload.put("function", "ENABLE_ROLLOUT");
        payload.put("appStatus", "REGISTRATION");
        payload.put("rolloutRate", record.getGrayPercent());
        return payload;
    }

    /**
     * 映射三星状态。
     */
    private ReleaseStatus mapSanxingStatus(String rawStatus, Map<String, Object> contentInfo, AppReleaseRecord record) {
        String normalized = rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "REJECT", "REJECTED" -> ReleaseStatus.REJECT;
            case "FOR_SALE", "READY_FOR_SALE", "SALE" -> ReleaseStatus.PASS;
            case "SUSPENDED", "TERMINATED", "STOP", "STOPPED" -> ReleaseStatus.OFFLINE;
            case "REGISTERING", "UPDATING", "RE_REGISTERING", "REGISTRATION" -> ReleaseStatus.AUDITING;
            default -> sanxingBinaryMatchesTargetVersion(contentInfo, record) ? ReleaseStatus.PASS : ReleaseStatus.AUDITING;
        };
    }

    /**
     * 处理三星 Binary Matches Target 版本相关逻辑。
     */
    private boolean sanxingBinaryMatchesTargetVersion(Map<String, Object> contentInfo, AppReleaseRecord record) {
        String expectedVersionCode = resolveSanxingExpectedVersionCode(record);
        if (!StringUtils.hasText(expectedVersionCode)) {
            return false;
        }
        Object binaryListValue = contentInfo.get("binaryList");
        if (!(binaryListValue instanceof List<?> binaryList)) {
            return false;
        }
        for (Object item : binaryList) {
            Map<String, Object> binary = asMap(item);
            String versionCode = firstString(binary, "versionCode");
            if (expectedVersionCode.equals(versionCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析三星 Expected 版本编码。
     */
    private String resolveSanxingExpectedVersionCode(AppReleaseRecord record) {
        String versionCode = record.getVersionCode();
        if (!StringUtils.hasText(versionCode) && record.getAppVersion() != null) {
            versionCode = record.getAppVersion().getVersionCode();
        }
        return StringUtils.hasText(versionCode) ? versionCode.trim() : "";
    }

    /**
     * 读取三星内容 Info。
     */
    private List<Map<String, Object>> readSanxingContentInfo(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Sanxing contentInfo returned unexpected response" + sanxingResponseBodySuffix(body), ex);
        }
    }

    /**
     * 获取首个三星内容 Info。
     */
    private Map<String, Object> firstSanxingContentInfo(List<Map<String, Object>> contentInfos, String contentId) {
        if (contentInfos == null || contentInfos.isEmpty()) {
            return Map.of();
        }
        for (Map<String, Object> contentInfo : contentInfos) {
            if (contentId.equals(firstString(contentInfo, "contentId", "ctntId"))) {
                return contentInfo;
            }
        }
        return contentInfos.get(0);
    }

    /**
     * 解析First Binary Seq。
     */
    private String resolveFirstBinarySeq(Map<String, Object> contentInfo) {
        Object binaryListValue = contentInfo.get("binaryList");
        if (binaryListValue instanceof List<?> binaryList && !binaryList.isEmpty()) {
            return firstString(asMap(binaryList.get(0)), "binarySeq");
        }
        return "";
    }

    /**
     * 构建三星文件 Part。
     */
    private HttpEntity<FileSystemResource> buildSanxingFilePart(Path filePath) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("file", filePath.getFileName().toString());
        return new HttpEntity<>(new FileSystemResource(filePath), headers);
    }

    /**
     * 解析三星上下文。
     */
    private SanxingContext resolveSanxingContext(AppVersion version, AppReleaseRecord record) {
        String packageLocation = firstNonBlank(version.getPackageUrl64(), version.getPackageUrl32(), version.getPackageUrl());
        ProjectMetadataContext metadataContext = resolveProjectMetadataContext(packageLocation);
        Map<String, Object> metadata = metadataContext.metadata();
        String contentId = resolveSanxingContentId(record, metadata);
        if (!StringUtils.hasText(contentId)) {
            throw new IllegalStateException("Sanxing submit requires app.publish-metadata.values.sanxing.contentId in application.yml.");
        }
        Path packagePath = resolveSanxingPackagePath(metadataContext, metadata, version);
        return new SanxingContext(
                contentId.trim(),
                normalizeSanxingYesNo(firstNonBlank(
                        stringValue(sanxingMetadata(metadata, "gms")),
                        "N"
                )),
                firstNonBlank(
                        stringValue(sanxingMetadata(metadata, "binarySeqForDeviceInfo")),
                        stringValue(sanxingMetadata(metadata, "deviceBinarySeq"))
                ),
                metadataContext,
                metadata,
                packagePath
        );
    }

    /**
     * 解析三星包路径。
     */
    private Path resolveSanxingPackagePath(
            ProjectMetadataContext metadataContext,
            Map<String, Object> metadata,
            AppVersion version
    ) {
        Object configuredLocation = sanxingMetadata(metadata, "apkPath");
        if (configuredLocation == null) {
            configuredLocation = sanxingMetadata(metadata, "packagePath");
        }
        Path configuredPath = resolveProjectAssetPath(metadataContext.metadataPath(), configuredLocation);
        if (configuredPath != null) {
            return configuredPath;
        }
        String packageLocation = configuredLocation == null
                ? firstNonBlank(version.getPackageUrl64(), version.getPackageUrl32(), version.getPackageUrl())
                : String.valueOf(configuredLocation);
        return requireLocalPackage(packageLocation, "Sanxing submit requires apk path");
    }

    /**
     * 解析三星内容 Id。
     */
    private String resolveSanxingContentId(AppReleaseRecord record, Map<String, Object> metadata) {
        String contentId = record == null ? null : record.getStoreReleaseId();
        if (!StringUtils.hasText(contentId)) {
            contentId = stringValue(sanxingMetadata(metadata, "contentId"));
        }
        return StringUtils.hasText(contentId) ? contentId.trim() : "";
    }

    /**
     * 处理三星元数据相关逻辑。
     */
    private Object sanxingMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        return firstNonNull(
                metadataLookup(metadata, "sanxing", key),
                metadataLookup(metadata, null, "sanxing" + Character.toUpperCase(key.charAt(0)) + key.substring(1)),
                metadataLookup(metadata, null, key)
        );
    }

    /**
     * 解析三星 Service Account Id。
     */
    private String resolveSanxingServiceAccountId(AppStoreConfig storeConfig) {
        if (!StringUtils.hasText(storeConfig.getClientId())) {
            throw new IllegalArgumentException("Sanxing token refresh requires clientId as service-account-id");
        }
        return storeConfig.getClientId().trim();
    }

    /**
     * 创建三星 Jwt。
     */
    private String createSanxingJwt(AppStoreConfig storeConfig, String serviceAccountId) {
        try {
            PrivateKey privateKey = parseSanxingPrivateKey(resolveSanxingPrivateKeyValue(storeConfig));
            long issuedAt = Instant.now().getEpochSecond();
            long expireAt = issuedAt + 1200L;
            String header = base64Url(writeJson(Map.of("alg", "RS256", "typ", "JWT")).getBytes(StandardCharsets.UTF_8));
            String claims = base64Url(writeJson(Map.of(
                    "iss", serviceAccountId,
                    "scopes", List.of("publishing"),
                    "iat", issuedAt,
                    "exp", expireAt
            )).getBytes(StandardCharsets.UTF_8));
            String unsignedToken = header + "." + claims;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(unsignedToken.getBytes(StandardCharsets.UTF_8));
            return unsignedToken + "." + base64Url(signature.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to build Sanxing JWT", ex);
        }
    }

    /**
     * 解析三星 Private Key 值。
     */
    private String resolveSanxingPrivateKeyValue(AppStoreConfig storeConfig) {
        if (!StringUtils.hasText(storeConfig.getPrivateKey())) {
            throw new IllegalArgumentException("Sanxing token refresh requires privateKey as service account key JSON or PEM");
        }
        String configuredValue = storeConfig.getPrivateKey().trim();
        Path possiblePath = toPath(configuredValue);
        String content = configuredValue;
        if (possiblePath != null && Files.exists(possiblePath) && Files.isRegularFile(possiblePath)) {
            try {
                content = Files.readString(possiblePath);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to read Sanxing private key file: " + possiblePath, ex);
            }
        }
        String extractedPem = extractSanxingPrivateKeyPem(content);
        return StringUtils.hasText(extractedPem) ? extractedPem : content;
    }

    /**
     * 提取三星 Private Key Pem。
     */
    private String extractSanxingPrivateKeyPem(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            Map<String, Object> json = objectMapper.readValue(trimmed, new TypeReference<>() {
            });
            return firstNonBlank(
                    firstString(json, "private_key", "privateKey", "privateKeyPem", "private_key_pem"),
                    firstString(asMap(json.get("createdItem")), "private_key", "privateKey")
            );
        } catch (JsonProcessingException ex) {
            return trimmed;
        }
    }

    /**
     * 解析三星 Private Key。
     */
    private PrivateKey parseSanxingPrivateKey(String privateKeyValue) throws GeneralSecurityException {
        String normalized = privateKeyValue
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(normalized);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    /**
     * 处理base64 URL相关逻辑。
     */
    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 处理三星响应报文 Suffix相关逻辑。
     */
    private String sanxingResponseBodySuffix(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "";
        }
        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 300) {
            normalized = normalized.substring(0, 300) + "...";
        }
        return ", body=" + normalized;
    }

    /**
     * 处理三星 Oauth 令牌 Mode相关逻辑。
     */
    private boolean sanxingOauthTokenMode(StoreApiProperties.StoreEndpointProperties endpoint) {
        String tokenEndpoint = sanxingTokenEndpoint(endpoint);
        return tokenEndpoint.toLowerCase(Locale.ROOT).contains("oauth/token");
    }

    /**
     * 规范化三星 Yes No。
     */
    private String normalizeSanxingYesNo(String value) {
        return "Y".equalsIgnoreCase(value) ? "Y" : "N";
    }

    /**
     * 确保三星 Success。
     */
    private void ensureSanxingSuccess(Map<String, Object> response, String action) {
        if (response == null || response.isEmpty()) {
            return;
        }
        if (response.get("ok") instanceof Boolean ok && ok) {
            return;
        }
        String resultCode = firstString(response, "resultCode", "result_code");
        if (StringUtils.hasText(resultCode)) {
            if ("0000".equals(resultCode)) {
                return;
            }
            throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Sanxing " + action + " failed: resultCode=" + resultCode + ", msg=" + firstNonBlank(firstString(response, "resultMessage", "message"), "unknown error"));
        }
        String errorCode = firstString(response, "errorCode", "error_code", "code");
        if (StringUtils.hasText(errorCode) && !"null".equalsIgnoreCase(errorCode)) {
            throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Sanxing " + action + " failed: errorCode=" + errorCode + ", msg=" + firstNonBlank(firstString(response, "errorMsg", "message"), "unknown error"));
        }
        if (response.containsKey("httpStatus") && !"OK".equalsIgnoreCase(firstString(response, "httpStatus"))) {
            throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Sanxing " + action + " failed: httpStatus=" + firstString(response, "httpStatus"));
        }
    }

    /**
     * 处理put If Has 文本相关逻辑。
     */
    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    /**
     * 处理put If Present相关逻辑。
     */
    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && !StringUtils.hasText(text)) {
            return;
        }
        target.put(key, value);
    }

    /**
     * 校验三星文本。
     */
    private String requireSanxingText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    /**
     * 处理字符串值相关逻辑。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 处理三星 Base URL相关逻辑。
     */
    private String sanxingBaseUrl(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getBaseUrl()) ? endpoint.getBaseUrl() : SANXING_BASE_URL;
    }

    /**
     * 处理三星令牌接口地址相关逻辑。
     */
    private String sanxingTokenEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getTokenEndpoint()) ? endpoint.getTokenEndpoint() : SANXING_TOKEN_ENDPOINT;
    }

    /**
     * 处理三星内容 Info 接口地址相关逻辑。
     */
    private String sanxingContentInfoEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getStatusEndpoint()) ? endpoint.getStatusEndpoint() : SANXING_CONTENT_INFO_ENDPOINT;
    }

    /**
     * 处理三星提交接口地址相关逻辑。
     */
    private String sanxingSubmitEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getSubmitEndpoint()) ? endpoint.getSubmitEndpoint() : SANXING_CONTENT_SUBMIT_ENDPOINT;
    }

    private record SanxingContext(
            String contentId,
            String gms,
            String binarySeqForDeviceInfo,
            ProjectMetadataContext metadataContext,
            Map<String, Object> metadata,
            Path packagePath
    ) {
    }
}
