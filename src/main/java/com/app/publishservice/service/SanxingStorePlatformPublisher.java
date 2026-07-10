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
import org.springframework.http.ResponseEntity;
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
    private static final String SANXING_STAGED_ROLLOUT_BINARY_ENDPOINT = "/seller/v2/content/stagedRolloutBinary";
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
        String serviceAccountId = resolveSanxingServiceAccountId(storeConfig);
        String jwt = resolveSanxingJwt(storeConfig, endpoint, serviceAccountId);
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
                            .body(String.class),
                    () -> writeJson(mockSanxingTokenResponse())
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
                            .body(String.class),
                    () -> writeJson(mockSanxingTokenResponse())
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
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        SanxingContext context = resolveSanxingSubmitContext(storeConfig, version, record, endpoint);

        List<Map<String, Object>> contentInfoList = querySanxingContentInfo(storeConfig, token, context.contentId());
        Map<String, Object> currentContentInfo = selectSanxingSubmitContentInfo(contentInfoList, context.contentId());
        if (currentContentInfo.isEmpty()) {
            throw new IllegalStateException("Sanxing contentInfo does not contain contentId: " + context.contentId());
        }

        Map<String, Object> contentUpdatePayload = buildSanxingContentUpdatePayload(version, context, currentContentInfo);
        Map<String, Object> queryStagedRolloutPayload = null;
        Map<String, Object> queryStagedRolloutResponse = null;
        Map<String, Object> disableStagedRolloutPayload = null;
        Map<String, Object> disableStagedRolloutResponse = null;
        if (record != null && isStagedRelease(record) && isSanxingSaleContent(currentContentInfo)) {
            queryStagedRolloutPayload = buildSanxingStagedRolloutQueryPayload(context.contentId(), "SALE");
            queryStagedRolloutResponse = querySanxingStagedRolloutRate(storeConfig, token, context.contentId(), "SALE");
            if (hasSanxingEnabledStagedRollout(queryStagedRolloutResponse)) {
                disableStagedRolloutPayload = buildSanxingDisableStagedRolloutPayload(context.contentId(), "SALE");
                disableStagedRolloutResponse = sanxingJsonRequest(
                        storeConfig,
                        token,
                        "PUT",
                        sanxingBaseUrl(endpoint) + SANXING_STAGED_ROLLOUT_ENDPOINT,
                        disableStagedRolloutPayload,
                        "disable sanxing staged rollout"
                );
            }
        }

        Map<String, Object> contentUpdateResponse = null;

        Map<String, Object> uploadSession = createSanxingUploadSession(storeConfig, token);
        Map<String, Object> uploadResult = uploadSanxingFile(storeConfig, token, context.packagePath(), uploadSession);
        String fileKey = firstString(uploadResult, "fileKey", "filekey");
        if (!StringUtils.hasText(fileKey)) {
            throw new IllegalStateException("Sanxing fileUpload response does not contain fileKey");
        }

        if (isSanxingSaleContent(currentContentInfo)) {
            contentUpdateResponse = sanxingJsonRequest(
                    storeConfig,
                    token,
                    "POST",
                    sanxingBaseUrl(endpoint) + SANXING_CONTENT_UPDATE_ENDPOINT,
                    contentUpdatePayload,
                    "update sanxing content"
            );
        }

        Map<String, Object> addBinaryPayload = buildSanxingAddBinaryPayload(context, currentContentInfo, fileKey);
        Map<String, Object> addBinaryResponse = addSanxingBinary(
                storeConfig,
                token,
                endpoint,
                context.contentId(),
                record,
                currentContentInfo,
                addBinaryPayload
        );

        if (contentUpdateResponse == null) {
            contentUpdateResponse = sanxingJsonRequest(
                    storeConfig,
                    token,
                    "POST",
                    sanxingBaseUrl(endpoint) + SANXING_CONTENT_UPDATE_ENDPOINT,
                    contentUpdatePayload,
                    "update sanxing content"
            );
        }

        Map<String, Object> stagedRolloutBinaryPayload = null;
        Map<String, Object> stagedRolloutBinaryResponse = null;
        Map<String, Object> stagedRolloutPayload = null;
        Map<String, Object> stagedRolloutResponse = null;
        if (record != null && isStagedRelease(record)) {
            String stagedRolloutBinarySeq = extractSanxingBinarySeq(addBinaryResponse);
            stagedRolloutBinaryPayload = buildSanxingStagedRolloutBinaryPayload(context.contentId(), stagedRolloutBinarySeq);
            stagedRolloutBinaryResponse = sanxingJsonRequest(
                    storeConfig,
                    token,
                    "PUT",
                    sanxingBaseUrl(endpoint) + SANXING_STAGED_ROLLOUT_BINARY_ENDPOINT,
                    stagedRolloutBinaryPayload,
                    "update sanxing staged rollout binary"
            );
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
        if (queryStagedRolloutPayload != null) {
            requestLog.put("queryStagedRollout", queryStagedRolloutPayload);
        }
        if (disableStagedRolloutPayload != null) {
            requestLog.put("disableStagedRollout", disableStagedRolloutPayload);
        }
        requestLog.put("addBinary", addBinaryPayload);
        requestLog.put("contentUpdate", contentUpdatePayload);
        if (stagedRolloutBinaryPayload != null) {
            requestLog.put("stagedRolloutBinary", stagedRolloutBinaryPayload);
        }
        if (stagedRolloutPayload != null) {
            requestLog.put("stagedRollout", stagedRolloutPayload);
        }

        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("contentInfo", contentInfoList);
        responseLog.put("createUploadSession", uploadSession);
        responseLog.put("fileUpload", uploadResult);
        if (queryStagedRolloutResponse != null) {
            responseLog.put("queryStagedRollout", queryStagedRolloutResponse);
        }
        if (disableStagedRolloutResponse != null) {
            responseLog.put("disableStagedRollout", disableStagedRolloutResponse);
        }
        responseLog.put("addBinary", addBinaryResponse);
        responseLog.put("contentUpdate", contentUpdateResponse);
        if (stagedRolloutBinaryResponse != null) {
            responseLog.put("stagedRolloutBinary", stagedRolloutBinaryResponse);
        }
        if (stagedRolloutResponse != null) {
            responseLog.put("stagedRollout", stagedRolloutResponse);
        }
        responseLog.put("submit", Map.of("status", 204));

        return new StoreSubmitResult(context.contentId(), writeJson(requestLog), writeJson(responseLog), "submit success");
    }

    private String resolveSanxingJwt(
            AppStoreConfig storeConfig,
            StoreApiProperties.StoreEndpointProperties endpoint,
            String serviceAccountId
    ) {
        return endpoint.isMockEnabled()
                ? "mock-sanxing-jwt"
                : createSanxingJwt(storeConfig, serviceAccountId);
    }

    private SanxingContext resolveSanxingSubmitContext(
            AppStoreConfig storeConfig,
            AppVersion version,
            AppReleaseRecord record,
            StoreApiProperties.StoreEndpointProperties endpoint
    ) {
        return endpoint.isMockEnabled()
                ? mockSanxingContext(storeConfig, version, record)
                : resolveSanxingContext(storeConfig, version, record);
    }

    /**
     * 查询三星审核。
     */
    private StoreReviewResult querySanxingReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        String contentId = resolveSanxingContentId(storeConfig, record, null);
        List<Map<String, Object>> contentInfoList = querySanxingContentInfo(storeConfig, token, contentId);
        Map<String, Object> contentInfo = findSanxingContentInfoByTargetVersion(contentInfoList, contentId, record);
        if (contentInfo.isEmpty()) {
            contentInfo = firstSanxingContentInfo(contentInfoList, contentId);
        }
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
                        .body(String.class),
                () -> writeJson(mockSanxingContentInfo(contentId))
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
                        .body(String.class),
                () -> writeJson(mockSanxingUploadSession())
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
                        .body(String.class),
                () -> writeJson(mockSanxingUploadResult(filePath))
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
                        .toBodilessEntity(),
                () -> ResponseEntity.ok().build()
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
                },
                () -> writeJson(mockSanxingJsonResponse(action, payload))
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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentId", context.contentId());
        //payload.put("contentStatus", "REGISTERING");
        payload.put("defaultLanguageCode", requireSanxingText(firstString(contentInfo, "defaultLanguageCode"), "Sanxing contentUpdate requires defaultLanguageCode"));
        payload.put("newFeature", appInfo == null ? firstString(contentInfo, "newFeature") : appInfo.getAppDescription());
        payload.put("paid", firstNonBlank(firstString(contentInfo, "paid"), "N"));
        payload.put("publicationType", firstNonBlank(
                firstString(contentInfo, "publicationType"),
                "01"
        ));
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

    private Map<String, Object> buildSanxingStagedRolloutQueryPayload(String contentId, String appStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentId", contentId);
        payload.put("appStatus", appStatus);
        return payload;
    }

    private Map<String, Object> buildSanxingDisableStagedRolloutPayload(String contentId, String appStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentId", contentId);
        payload.put("function", "DISABLE_ROLLOUT");
        payload.put("appStatus", appStatus);
        return payload;
    }

    private Map<String, Object> buildSanxingStagedRolloutBinaryPayload(String contentId, String binarySeq) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentId", contentId);
        payload.put("function", "ADD");
        payload.put("binarySeq", requireSanxingText(binarySeq, "Sanxing staged rollout binary requires binarySeq"));
        return payload;
    }

    private Map<String, Object> addSanxingBinary(
            AppStoreConfig storeConfig,
            String token,
            StoreApiProperties.StoreEndpointProperties endpoint,
            String contentId,
            AppReleaseRecord record,
            Map<String, Object> contentInfo,
            Map<String, Object> addBinaryPayload
    ) {
        try {
            return sanxingJsonRequest(
                    storeConfig,
                    token,
                    "POST",
                    sanxingBaseUrl(endpoint) + SANXING_ADD_BINARY_ENDPOINT,
                    addBinaryPayload,
                    "add sanxing binary"
            );
        } catch (StoreApiException ex) {
            if (isSanxingBinaryAlreadyInUse(ex)) {
                List<Map<String, Object>> latestContentInfos = querySanxingContentInfo(storeConfig, token, contentId);
                Map<String, Object> matchedContentInfo = findSanxingContentInfoByTargetVersion(latestContentInfos, contentId, record);
                if (!sanxingBinaryMatchesTargetVersion(matchedContentInfo, record)) {
                    throw ex;
                }
                String existingBinarySeq = resolveSanxingBinarySeqForRecord(matchedContentInfo, record);
                log.info(
                        "Sanxing add binary returned already-in-use for target version, reuse existing binary, contentId={}, versionCode={}, binarySeq={}",
                        contentId,
                        resolveSanxingExpectedVersionCode(record),
                        existingBinarySeq
                );
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("resultCode", "0000");
                response.put("resultMessage", "This binary is already in use.");
                if (StringUtils.hasText(existingBinarySeq)) {
                    response.put("binarySeq", existingBinarySeq);
                    response.put("data", Map.of("binarySeq", existingBinarySeq));
                }
                response.put("contentInfoRefreshed", Boolean.TRUE);
                response.put("reusedExistingBinary", Boolean.TRUE);
                return response;
            }
            throw ex;
        }
    }

    private String extractSanxingBinarySeq(Map<String, Object> addBinaryResponse) {
        return firstNonBlank(
                firstString(addBinaryResponse, "binarySeq"),
                firstString(asMap(addBinaryResponse.get("data")), "binarySeq")
        );
    }

    private Map<String, Object> querySanxingStagedRolloutRate(
            AppStoreConfig storeConfig,
            String token,
            String contentId,
            String appStatus
    ) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("contentId", contentId);
        queryParams.put("appStatus", appStatus);
        String url = sanxingBaseUrl(endpoint) + SANXING_STAGED_ROLLOUT_ENDPOINT;
        String responseBody;
        try {
            responseBody = executeStoreRequest(
                    trace(storeConfig, "query sanxing staged rollout", "GET", url, queryParams, Map.of("service-account-id", resolveSanxingServiceAccountId(storeConfig))),
                    () -> restClient.get()
                            .uri(url + "?" + buildQueryString(queryParams))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("service-account-id", resolveSanxingServiceAccountId(storeConfig))
                            .retrieve()
                            .body(String.class),
                    () -> writeJson(mockSanxingStagedRolloutRateResponse(false))
            );
        } catch (StoreApiException ex) {
            if (isSanxingEmptyStagedRolloutResponse(ex)) {
                log.info("Sanxing staged rollout query returned no rollout binaries, contentId={}, appStatus={}", contentId, appStatus);
                return emptySanxingStagedRolloutResponse();
            }
            throw ex;
        }
        Map<String, Object> response = readJson(responseBody);
        ensureSanxingSuccess(response, "query sanxing staged rollout");
        return response;
    }

    private boolean hasSanxingEnabledStagedRollout(Map<String, Object> response) {
        Map<String, Object> data = asMap(response.get("data"));
        Integer rolloutRate = firstInteger(
                data.get("rolloutRate"),
                response.get("rolloutRate")
        );
        if (rolloutRate != null && rolloutRate > 0) {
            return true;
        }
        Object countriesValue = firstNonNull(data.get("countries"), response.get("countries"));
        if (countriesValue instanceof List<?> countries) {
            for (Object entry : countries) {
                Integer countryRate = firstInteger(asMap(entry).get("rolloutRate"));
                if (countryRate != null && countryRate > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 映射三星状态。
     */
    private boolean isSanxingEmptyStagedRolloutResponse(StoreApiException ex) {
        if (ex == null || ex.getStatus() != HttpStatus.BAD_GATEWAY) {
            return false;
        }
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("no binaries set to rollout")
                || normalized.contains("there are currently no binaries set to rollout for this content");
    }

    private Map<String, Object> emptySanxingStagedRolloutResponse() {
        return Map.of(
                "resultCode", "0000",
                "data", Map.of("rolloutRate", 0)
        );
    }

    private ReleaseStatus mapSanxingStatus(String rawStatus, Map<String, Object> contentInfo, AppReleaseRecord record) {
        String normalized = rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "REJECT", "REJECTED",
                    "PRE_REVIEWS_REJECTED", "CONTENT_REVIEW_REJECTED", "DEVICE_TEST_REJECTED", "TEST_CONFIRMATION_REJECTED",
                    "BETA_PRE_REVIEW_REJECTED",
                    "CANCELED", "PRE_REVIEWS_CANCELED", "CONTENT_REVIEW_CANCELED", "DEVICE_TEST_CANCELED", "TEST_CONFIRMATION_CANCELED" -> ReleaseStatus.REJECT;
            case "FOR_SALE", "READY_FOR_SALE", "SALE", "BETA_DEPLOYED" -> ReleaseStatus.PASS;
            case "SUSPENDED", "TERMINATED", "STOP", "STOPPED", "BETA_SUSPENDED" -> ReleaseStatus.OFFLINE;
            case "REGISTERING", "UPDATING", "RE_REGISTERING", "REGISTRATION",
                    "READY_FOR_REVIEW",
                    "READY_TO_PRE_REVIEWS", "UNDER_PRE_REVIEWS", "PRE_REVIEWS_SUSPENDED", "PRE_REVIEWS_DELAYED",
                    "READY_FOR_CONTENT_REVIEW", "UNDER_CONTENT_REVIEW", "CONTENT_REVIEW_SUSPENDED", "CONTENT_REVIEW_DELAYED",
                    "READY_FOR_DEVICE_TEST", "UNDER_DEVICE_TEST", "DEVICE_TEST_SUSPENDED", "DEVICE_TEST_DELAYED",
                    "READY_FOR_TEST_CONFIRMATION", "UNDER_TEST_CONFIRMATION", "TEST_CONFIRMATION_SUSPENDED", "TEST_CONFIRMATION_DELAYED",
                    "READY_FOR_CHANGE",
                    "BETA_REGISTERING", "READY_FOR_BETA_TESTING", "BETA_UPDATING" -> ReleaseStatus.AUDITING;
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
        return sanxingContentInfoContainsVersion(contentInfo, expectedVersionCode);
    }

    /**
     * 解析三星 Expected 版本编码。
     */
    private String resolveSanxingExpectedVersionCode(AppReleaseRecord record) {
        String versionCode = record.getVersionCode();
        if (!StringUtils.hasText(versionCode) && record.getAppVersion() != null) {
            versionCode = record.getAppVersion().getVersionCode();
        }
        return StringUtils.hasText(versionCode) ? versionCode.trim().replaceAll("\\.","") : "";
    }

    private String resolveSanxingBinarySeqForRecord(Map<String, Object> contentInfo, AppReleaseRecord record) {
        String expectedVersionCode = resolveSanxingExpectedVersionCode(record);
        if (!StringUtils.hasText(expectedVersionCode)) {
            return "";
        }
        return resolveSanxingBinarySeq(contentInfo, expectedVersionCode);
    }

    private boolean sanxingContentInfoContainsVersion(Map<String, Object> contentInfo, String expectedVersionCode) {
        Object binaryListValue = contentInfo.get("binaryList");
        if (!(binaryListValue instanceof List<?> binaryList)) {
            return false;
        }
        for (Object item : binaryList) {
            Map<String, Object> binary = asMap(item);
            if (expectedVersionCode.equals(firstString(binary, "versionCode"))) {
                return true;
            }
        }
        return false;
    }

    private String resolveSanxingBinarySeq(Map<String, Object> contentInfo, String expectedVersionCode) {
        Object binaryListValue = contentInfo.get("binaryList");
        if (!(binaryListValue instanceof List<?> binaryList)) {
            return "";
        }
        for (Object item : binaryList) {
            Map<String, Object> binary = asMap(item);
            if (expectedVersionCode.equals(firstString(binary, "versionCode"))) {
                return firstString(binary, "binarySeq");
            }
        }
        return "";
    }

    /**
     * 读取三星内容 Info。
     */
    private SanxingContext mockSanxingContext(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record) {
        String packageLocation = firstNonBlank(version.getPackageUrl64(), version.getPackageUrl32(), version.getPackageUrl());
        ProjectMetadataContext metadataContext = resolveProjectMetadataContext(packageLocation);
        String contentId = firstNonBlank(
                record == null ? null : record.getStoreReleaseId(),
                storeConfig == null ? null : storeConfig.getAppId(),
                version == null || version.getAppInfo() == null ? null : version.getAppInfo().getPackageName(),
                "mock-sanxing-" + UUID.randomUUID()
        );
        Path packagePath = requireLocalPackage(packageLocation, "Sanxing submit requires apk path");
        return new SanxingContext(
                contentId,
                "N",
                "",
                metadataContext,
                Map.of(),
                packagePath
        );
    }

    private List<Map<String, Object>> mockSanxingContentInfo(String contentId) {
        Map<String, Object> saleContentInfo = new LinkedHashMap<>();
        saleContentInfo.put("contentId", contentId);
        saleContentInfo.put("contentStatus", "FOR_SALE");
        saleContentInfo.put("appTitle", "Mock Sanxing App");
        saleContentInfo.put("defaultLanguageCode", "EN");
        saleContentInfo.put("applicationType", "android");
        saleContentInfo.put("longDescription", "Mock Sanxing long description");
        saleContentInfo.put("shortDescription", "Mock Sanxing short description");
        saleContentInfo.put("newFeature", "Mock Sanxing new feature");
        saleContentInfo.put("ageLimit", "0");
        saleContentInfo.put("chinaAgeLimit", "0");
        saleContentInfo.put("openSourceURL", "");
        saleContentInfo.put("paid", "N");
        saleContentInfo.put("publicationType", "01");
        saleContentInfo.put("privatePolicyURLYN", "Y");
        saleContentInfo.put("privatePolicyURL", "https://mock.sanxing.local/privacy");
        saleContentInfo.put("youTubeURL", "");
        saleContentInfo.put("copyrightHolder", "");
        saleContentInfo.put("supportEMail", "mock@sanxing.local");
        saleContentInfo.put("supportedSiteUrl", "");
        saleContentInfo.put("standardPrice", "0");
        saleContentInfo.put("reviewComment", "Mock Sanxing review comment");
        saleContentInfo.put("reviewFilename", "mock-review.txt");
        saleContentInfo.put("reviewFilekey", "mock-review-file-key");
        saleContentInfo.put("iconKey", "mock-icon-key");
        saleContentInfo.put("heroImageKey", "mock-hero-key");
        saleContentInfo.put("supportedLanguages", List.of("EN"));
        saleContentInfo.put("addLanguage", List.of(Map.of("languageCode", "EN")));
        saleContentInfo.put("screenshots", List.of(Map.of("imageKey", "mock-shot-1")));
        saleContentInfo.put("sellCountryList", List.of("US", "KR"));
        saleContentInfo.put("usExportLaws", Boolean.TRUE);
        saleContentInfo.put("binaryList", List.of(Map.of(
                "versionCode", "99",
                "binarySeq", "1"
        )));

        Map<String, Object> registeringContentInfo = new LinkedHashMap<>(saleContentInfo);
        registeringContentInfo.put("contentStatus", "REGISTERING");
        registeringContentInfo.put("binaryList", List.of(Map.of(
                "versionCode", "100",
                "binarySeq", "2"
        )));

        return List.of(saleContentInfo, registeringContentInfo);
    }

    private Map<String, Object> mockSanxingUploadSession() {
        return Map.of(
                "sessionId", "mock-sanxing-session",
                "url", SANXING_UPLOAD_URL
        );
    }

    private Map<String, Object> mockSanxingUploadResult(Path filePath) {
        return Map.of(
                "resultCode", "0000",
                "fileKey", "mock-file-key-" + filePath.getFileName()
        );
    }

    private Map<String, Object> mockSanxingStagedRolloutRateResponse(boolean enabled) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rolloutRate", enabled ? 30 : 0);
        data.put("countries", enabled ? List.of(Map.of("countryCode", "USA", "rolloutRate", 35)) : List.of());
        return Map.of(
                "resultCode", "0000",
                "resultMessage", "Ok",
                "data", data
        );
    }

    private Map<String, Object> mockSanxingJsonResponse(String action, Map<String, Object> payload) {
        if ("add sanxing binary".equals(action)) {
            return Map.of(
                    "resultCode", "0000",
                    "fileKey", firstString(payload, "filekey"),
                    "data", Map.of("binarySeq", "mock-binary-seq")
            );
        }
        if ("update sanxing content".equals(action)) {
            return Map.of(
                    "resultCode", "0000",
                    "contentId", firstString(payload, "contentId")
            );
        }
        if ("disable sanxing staged rollout".equals(action)) {
            return Map.of(
                    "resultCode", "0000",
                    "contentId", firstString(payload, "contentId")
            );
        }
        if ("update sanxing staged rollout binary".equals(action)) {
            return Map.of(
                    "resultCode", "0000",
                    "contentId", firstString(payload, "contentId"),
                    "binarySeq", firstString(payload, "binarySeq")
            );
        }
        if ("update sanxing staged rollout".equals(action)) {
            return Map.of(
                    "resultCode", "0000",
                    "contentId", firstString(payload, "contentId"),
                    "rolloutRate", payload.get("rolloutRate")
            );
        }
        return Map.of("resultCode", "0000");
    }

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

    private Map<String, Object> selectSanxingSubmitContentInfo(List<Map<String, Object>> contentInfos, String contentId) {
        if (contentInfos == null || contentInfos.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> matchedContentInfo = Map.of();
        for (Map<String, Object> contentInfo : contentInfos) {
            if (!contentId.equals(firstString(contentInfo, "contentId", "ctntId"))) {
                continue;
            }
            if (isSanxingSaleContent(contentInfo)) {
                return contentInfo;
            }
            if (matchedContentInfo.isEmpty()) {
                matchedContentInfo = contentInfo;
            }
        }
        return matchedContentInfo.isEmpty() ? contentInfos.get(0) : matchedContentInfo;
    }

    private Map<String, Object> findSanxingContentInfoByTargetVersion(
            List<Map<String, Object>> contentInfos,
            String contentId,
            AppReleaseRecord record
    ) {
        String expectedVersionCode = resolveSanxingExpectedVersionCode(record);
        if (!StringUtils.hasText(expectedVersionCode) || contentInfos == null || contentInfos.isEmpty()) {
            return Map.of();
        }
        for (Map<String, Object> contentInfo : contentInfos) {
            if (!contentId.equals(firstString(contentInfo, "contentId", "ctntId"))) {
                continue;
            }
            if (sanxingContentInfoContainsVersion(contentInfo, expectedVersionCode)) {
                return contentInfo;
            }
        }
        return Map.of();
    }

    private boolean isSanxingSaleContent(Map<String, Object> contentInfo) {
        String normalized = normalizeSanxingStatus(firstNonBlank(
                firstString(contentInfo, "contentStatus"),
                firstString(contentInfo, "appStatus"),
                firstString(contentInfo, "status")
        ));
        return "FOR_SALE".equals(normalized)
                || "READY_FOR_SALE".equals(normalized)
                || "SALE".equals(normalized);
    }

    private String normalizeSanxingStatus(String rawStatus) {
        return rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
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
    private SanxingContext resolveSanxingContext(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record) {
        String packageLocation = firstNonBlank(version.getPackageUrl64(), version.getPackageUrl32(), version.getPackageUrl());
        ProjectMetadataContext metadataContext = resolveProjectMetadataContext(packageLocation);
        Map<String, Object> metadata = metadataContext.metadata();
        String contentId = resolveSanxingContentId(storeConfig, record, metadata);
        if (!StringUtils.hasText(contentId)) {
            throw new IllegalStateException("Sanxing submit requires store config appId or app.publish-metadata.values.sanxing.contentId in application.yml.");
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
    private String resolveSanxingContentId(AppStoreConfig storeConfig, AppReleaseRecord record, Map<String, Object> metadata) {
        String contentId = record == null ? null : record.getStoreReleaseId();
        if (!StringUtils.hasText(contentId)) {
            contentId = storeConfig == null ? null : storeConfig.getAppId();
        }
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
        if (!StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Sanxing token refresh requires privateKey as service account key JSON or PEM");
        }
        String configuredValue = storeConfig.getClientSecret().trim();
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
    private Map<String, Object> mockSanxingTokenResponse() {
        return Map.of(
                "resultCode", "0000",
                "createdItem", Map.of("accessToken", "mock-sanxing-" + UUID.randomUUID())
        );
    }

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

    private boolean isSanxingBinaryAlreadyInUse(StoreApiException ex) {
        if (ex == null || !StringUtils.hasText(ex.getMessage())) {
            return false;
        }
        String normalized = ex.getMessage().toLowerCase(Locale.ROOT);
        return  normalized.contains("binary is already in use");
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
