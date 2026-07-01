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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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

final class RongyaoStorePlatformPublisher extends AbstractStorePlatformPublisher implements StorePlatformPublisher {

    private static final String RONGYAO_PUBLISH_BASE_URL = "https://appmarket-openapi-drcn.cloud.honor.com";
    private static final String RONGYAO_IAM_BASE_URL = "https://iam.developer.honor.com";
    private static final String RONGYAO_TOKEN_ENDPOINT = "/auth/token";
    private static final String RONGYAO_APP_ID_ENDPOINT = "/openapi/v1/publish/get-app-id";
    private static final String RONGYAO_APP_DETAIL_ENDPOINT = "/openapi/v1/publish/get-app-detail";
    private static final String RONGYAO_FILE_UPLOAD_URL_ENDPOINT = "/openapi/v1/publish/get-file-upload-url";
    private static final String RONGYAO_FILE_UPLOAD_ENDPOINT = "/openapi/v1/publish/file-upload";
    private static final String RONGYAO_UPDATE_FILE_INFO_ENDPOINT = "/openapi/v1/publish/update-file-info";
    private static final String RONGYAO_UPDATE_LANGUAGE_INFO_ENDPOINT = "/openapi/v1/publish/update-language-info";
    private static final String RONGYAO_SUBMIT_AUDIT_ENDPOINT = "/openapi/v1/publish/submit-audit";
    private static final String RONGYAO_AUDIT_RESULT_ENDPOINT = "/openapi/v1/publish/get-audit-result";
    private static final String RONGYAO_CURRENT_RELEASE_ENDPOINT = "/openapi/v1/publish/get-app-current-release";
    private static final String RONGYAO_PHASED_RELEASE_INFO_ENDPOINT = "/openapi/v1/publish/get-phased-release-info";
    private static final int RONGYAO_PACKAGE_FILE_TYPE = 100;
    private static final ZoneId RONGYAO_ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter RONGYAO_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    RongyaoStorePlatformPublisher(
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
                && "rongyao".equalsIgnoreCase(storeConfig.getStoreType().getCode());
    }

    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (!StringUtils.hasText(storeConfig.getClientId()) || !StringUtils.hasText(storeConfig.getClientSecret())) {
            throw new IllegalArgumentException("Rongyao token refresh requires clientId and clientSecret");
        }

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", storeConfig.getClientId().trim());
        body.add("client_secret", storeConfig.getClientSecret().trim());

        String tokenUrl = rongyaoTokenUrl(endpoint);
        String responseBody = executeStoreRequest(
                trace(storeConfig, "refresh rongyao token", "POST", tokenUrl, null, body),
                () -> restClient.post()
                        .uri(tokenUrl)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(body)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockRongyaoTokenResponse())
        );
        Map<String, Object> response = readJson(responseBody);
        String token = firstString(response, "access_token", "accessToken", "token");
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("Rongyao token response does not contain access_token");
        }
        long expiresIn = longValue(firstNonNull(response.get("expires_in"), response.get("expiresIn")), 3600L);
        return new TokenPayload(TokenType.ACCESS_TOKEN.getCode(), token, LocalDateTime.now().plusSeconds(expiresIn));
    }

    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return submitRongyaoRelease(storeConfig, version, record, token);
        }
    }

    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return queryRongyaoReview(storeConfig, record, token);
        }
    }

    private StoreSubmitResult submitRongyaoRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        AppInfo appInfo = version.getAppInfo();
        if (appInfo == null || !StringUtils.hasText(appInfo.getPackageName())) {
            throw new IllegalArgumentException("Rongyao submit requires app packageName");
        }

        String appId = queryRongyaoAppId(storeConfig, token, appInfo.getPackageName());
        Map<String, Object> appDetailResponse = queryRongyaoAppDetail(storeConfig, token, appId);
        Map<String, Object> appDetailData = extractRongyaoDataMap(appDetailResponse);
        List<Path> packagePaths = resolveRongyaoPackagePaths(version);
        List<Map<String, Object>> uploadPaths = getRongyaoFileUploadPaths(storeConfig, token, appId, packagePaths);
        List<Map<String, Object>> uploadResponses = uploadRongyaoPackages(storeConfig, token, appId, packagePaths, uploadPaths);
        Map<String, Object> updateFileInfoBody = buildRongyaoUpdateFileInfoBody(uploadPaths);
        Map<String, Object> updateFileInfoResponse = updateRongyaoFileInfo(storeConfig, token, appId, updateFileInfoBody);
        Map<String, Object> updateLanguageInfoBody = buildRongyaoUpdateLanguageInfoBody(version, appDetailData);
        Map<String, Object> updateLanguageInfoResponse = updateRongyaoLanguageInfo(storeConfig, token, appId, updateLanguageInfoBody);
        Map<String, Object> submitBody = buildRongyaoSubmitBody(version, record, packagePaths.isEmpty() ? null : packagePaths.get(0));
        Map<String, Object> submitResponse = submitRongyaoAudit(storeConfig, token, appId, submitBody);

        String storeReleaseId = stringValue(submitResponse.get("data"));
        if (!StringUtils.hasText(storeReleaseId)) {
            throw new IllegalStateException("Rongyao submit audit response does not contain releaseId");
        }

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("appIdQuery", Map.of("pkgName", appInfo.getPackageName()));
        requestLog.put("appDetail", Map.of("appId", appId));
        requestLog.put("fileUploadUrl", uploadPaths);
        requestLog.put("fileUpload", uploadResponses);
        requestLog.put("updateFileInfo", updateFileInfoBody);
        requestLog.put("updateLanguageInfo", updateLanguageInfoBody);
        requestLog.put("submitAudit", submitBody);

        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("appIdQuery", Map.of("appId", appId));
        responseLog.put("appDetail", appDetailResponse);
        responseLog.put("fileUploadUrl", uploadPaths);
        responseLog.put("fileUpload", uploadResponses);
        responseLog.put("updateFileInfo", updateFileInfoResponse);
        responseLog.put("updateLanguageInfo", updateLanguageInfoResponse);
        responseLog.put("submitAudit", submitResponse);

        String message = firstString(submitResponse, "msg", "message");
        return new StoreSubmitResult(
                storeReleaseId,
                writeJson(requestLog),
                writeJson(responseLog),
                StringUtils.hasText(message) ? message : "submit success"
        );
    }

    private StoreReviewResult queryRongyaoReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        if (!StringUtils.hasText(record.getPackageName())) {
            throw new IllegalArgumentException("Rongyao review query requires packageName");
        }
        if (!StringUtils.hasText(record.getStoreReleaseId())) {
            throw new IllegalArgumentException("Rongyao review query requires storeReleaseId");
        }

        String appId = queryRongyaoAppId(storeConfig, token, record.getPackageName());
        Map<String, Object> auditResultResponse = queryRongyaoAuditResult(storeConfig, token, appId, record.getStoreReleaseId());
        Map<String, Object> auditResult = findRongyaoAuditResult(auditResultResponse, record.getStoreReleaseId());
        int auditStatus = intValue(auditResult.get("auditResult"));
        String rejectReason = firstString(auditResult, "auditMessage", "msg", "message");

        if (auditStatus == 2) {
            return new StoreReviewResult(ReleaseStatus.REJECT, writeJson(auditResultResponse), StringUtils.hasText(rejectReason) ? rejectReason : null);
        }
        if (auditStatus == 0) {
            return new StoreReviewResult(ReleaseStatus.AUDITING, writeJson(auditResultResponse), null);
        }
        if (auditStatus == 1) {
            if (isStagedRelease(record)) {
                Map<String, Object> phasedReleaseInfo = queryRongyaoPhasedReleaseInfo(storeConfig, token, appId);
                ReleaseStatus stagedStatus = mapRongyaoPhasedReleaseStatus(extractRongyaoDataMap(phasedReleaseInfo));
                return new StoreReviewResult(stagedStatus, writeJson(phasedReleaseInfo), null);
            }
            return new StoreReviewResult(ReleaseStatus.PASS, writeJson(auditResultResponse), null);
        }

        if (isStagedRelease(record)) {
            Map<String, Object> phasedReleaseInfo = queryRongyaoPhasedReleaseInfo(storeConfig, token, appId);
            Map<String, Object> phasedData = extractRongyaoDataMap(phasedReleaseInfo);
            if (!phasedData.isEmpty()) {
                return new StoreReviewResult(mapRongyaoPhasedReleaseStatus(phasedData), writeJson(phasedReleaseInfo), null);
            }
        }

        Map<String, Object> currentReleaseResponse = queryRongyaoCurrentRelease(storeConfig, token, appId);
        Map<String, Object> currentReleaseData = extractRongyaoDataMap(currentReleaseResponse);
        String currentReleaseId = firstString(currentReleaseData, "releaseId");
        if (!StringUtils.hasText(currentReleaseId) || record.getStoreReleaseId().equals(currentReleaseId)) {
            int currentAuditResult = intValue(currentReleaseData.get("auditResult"));
            String currentRejectReason = firstString(currentReleaseData, "auditMessage");
            ReleaseStatus currentStatus = mapRongyaoAuditResult(currentAuditResult, isStagedRelease(record));
            if (currentStatus != ReleaseStatus.AUDITING) {
                return new StoreReviewResult(
                        currentStatus,
                        writeJson(currentReleaseResponse),
                        currentStatus == ReleaseStatus.REJECT && StringUtils.hasText(currentRejectReason) ? currentRejectReason : null
                );
            }
        }

        return new StoreReviewResult(ReleaseStatus.AUDITING, writeJson(auditResultResponse), null);
    }

    private String queryRongyaoAppId(AppStoreConfig storeConfig, String token, String packageName) {
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("pkgName", packageName);
        String url = rongyaoPublishBaseUrl(endpoint(storeConfig)) + rongyaoAppIdEndpoint() + "?" + buildQueryString(queryParams);
        String responseBody = executeStoreRequest(
                trace(storeConfig, "query rongyao app id", "GET", url, queryParams, Map.of("Authorization", "Bearer " + token)),
                () -> restClient.get()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockRongyaoAppIdResponse(packageName))
        );
        Map<String, Object> response = readJson(responseBody);
        ensureRongyaoSuccess(response, "query app id");
        Object dataValue = response.get("data");
        if (dataValue instanceof List<?> dataList) {
            for (Object item : dataList) {
                String appId = firstString(asMap(item), "appId", "id");
                if (StringUtils.hasText(appId)) {
                    return appId;
                }
            }
        }
        throw new IllegalStateException("Rongyao appId not found for packageName: " + packageName);
    }

    private List<Path> resolveRongyaoPackagePaths(AppVersion version) {
        return List.of(requireLocalPackage(version.getPackageUrl64(), "Rongyao submit requires app_version.package_url_64"));
    }

    private Map<String, Object> queryRongyaoAppDetail(AppStoreConfig storeConfig, String token, String appId) {
        String url = rongyaoPublishBaseUrl(endpoint(storeConfig)) + rongyaoAppDetailEndpoint() + "?appId=" + appId;
        String responseBody = executeStoreRequest(
                trace(storeConfig, "query rongyao app detail", "GET", url, Map.of("appId", appId), Map.of("Authorization", "Bearer " + token)),
                () -> restClient.get()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockRongyaoAppDetailResponse())
        );
        Map<String, Object> response = readJson(responseBody);
        ensureRongyaoSuccess(response, "query app detail");
        return response;
    }

    private List<Map<String, Object>> getRongyaoFileUploadPaths(
            AppStoreConfig storeConfig,
            String token,
            String appId,
            List<Path> packagePaths
    ) {
        List<Map<String, Object>> body = new ArrayList<>();
        for (Path packagePath : packagePaths) {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("fileName", packagePath.getFileName().toString());
            file.put("fileType", RONGYAO_PACKAGE_FILE_TYPE);
            file.put("fileSize", fileSize(packagePath));
            file.put("fileSha256", sha256Hex(packagePath));
            body.add(file);
        }

        String url = rongyaoPublishBaseUrl(endpoint(storeConfig)) + rongyaoFileUploadUrlEndpoint() + "?appId=" + appId;
        String responseBody = executeStoreRequest(
                trace(storeConfig, "get rongyao file upload url", "POST", url, Map.of("appId", appId), body),
                () -> restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockRongyaoFileUploadUrlResponse(packagePaths))
        );
        Map<String, Object> response = readJson(responseBody);
        ensureRongyaoSuccess(response, "get file upload url");

        List<Map<String, Object>> uploadPaths = new ArrayList<>();
        Object dataValue = response.get("data");
        if (dataValue instanceof List<?> dataList) {
            for (Object item : dataList) {
                Map<String, Object> uploadPath = asMap(item);
                if (StringUtils.hasText(firstString(uploadPath, "fileName")) && StringUtils.hasText(firstString(uploadPath, "uploadUrl"))) {
                    uploadPaths.add(uploadPath);
                }
            }
        }
        if (uploadPaths.size() != packagePaths.size()) {
            throw new IllegalStateException("Rongyao upload url response count does not match package count");
        }
        return uploadPaths;
    }

    private List<Map<String, Object>> uploadRongyaoPackages(
            AppStoreConfig storeConfig,
            String token,
            String appId,
            List<Path> packagePaths,
            List<Map<String, Object>> uploadPaths
    ) {
        List<Map<String, Object>> uploadResponses = new ArrayList<>();
        for (int index = 0; index < packagePaths.size(); index++) {
            Path packagePath = packagePaths.get(index);
            Map<String, Object> uploadPath = uploadPaths.get(index);
            String objectId = firstString(uploadPath, "objectId");
            if (!StringUtils.hasText(objectId)) {
                throw new IllegalStateException("Rongyao upload url response does not contain objectId");
            }
            String url = rongyaoPublishBaseUrl(endpoint(storeConfig))
                    + rongyaoFileUploadEndpoint()
                    + "?appId="
                    + appId
                    + "&objectId="
                    + objectId;

            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            fileHeaders.setContentDispositionFormData("file", packagePath.getFileName().toString());
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new HttpEntity<>(new org.springframework.core.io.FileSystemResource(packagePath), fileHeaders));

            String responseBody = executeStoreRequest(
                    trace(
                            storeConfig,
                            "upload rongyao package",
                            "POST",
                            url,
                            Map.of("appId", appId, "objectId", objectId),
                            Map.of("fileName", packagePath.getFileName().toString())
                    ),
                    () -> restClient.post()
                            .uri(url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(body)
                            .retrieve()
                            .body(String.class),
                    () -> writeJson(mockRongyaoUploadFileResponse(packagePath, objectId))
            );
            Map<String, Object> response = readJson(responseBody);
            ensureRongyaoSuccess(response, "upload file");
            Map<String, Object> uploadResponse = new LinkedHashMap<>();
            uploadResponse.put("fileName", packagePath.getFileName().toString());
            uploadResponse.put("objectId", objectId);
            uploadResponse.put("response", response);
            uploadResponses.add(uploadResponse);
        }
        return uploadResponses;
    }

    private Map<String, Object> buildRongyaoUpdateFileInfoBody(List<Map<String, Object>> uploadPaths) {
        List<Map<String, Object>> bindingFileList = new ArrayList<>();
        for (Map<String, Object> uploadPath : uploadPaths) {
            Object objectId = toNumericIfPossible(uploadPath.get("objectId"));
            bindingFileList.add(Map.of("objectId", objectId));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bindingFileList", bindingFileList);
        return body;
    }

    private Map<String, Object> updateRongyaoFileInfo(AppStoreConfig storeConfig, String token, String appId, Map<String, Object> body) {
        String url = rongyaoPublishBaseUrl(endpoint(storeConfig)) + rongyaoUpdateFileInfoEndpoint() + "?appId=" + appId;
        String responseBody = executeStoreRequest(
                trace(storeConfig, "update rongyao file info", "POST", url, Map.of("appId", appId), body),
                () -> restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockRongyaoSuccessResponse())
        );
        Map<String, Object> response = readJson(responseBody);
        ensureRongyaoSuccess(response, "update file info");
        return response;
    }

    private Map<String, Object> buildRongyaoUpdateLanguageInfoBody(AppVersion version, Map<String, Object> appDetailData) {
        AppInfo appInfo = version == null ? null : version.getAppInfo();
        String appDescription = appInfo == null ? null : appInfo.getAppDescription();
        List<Map<String, Object>> languageInfoList = new ArrayList<>();
        Object languageInfoValue = appDetailData.get("languageInfo");
        if (languageInfoValue instanceof List<?> entries) {
            for (Object entry : entries) {
                Map<String, Object> languageInfo = new LinkedHashMap<>(asMap(entry));
                languageInfo.put("newFeature", firstNonBlank(appDescription, firstString(languageInfo, "newFeature")));
                languageInfoList.add(languageInfo);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("setAll", 0);
        body.put("languageInfoList", languageInfoList);
        return body;
    }

    private Map<String, Object> updateRongyaoLanguageInfo(AppStoreConfig storeConfig, String token, String appId, Map<String, Object> body) {
        String url = rongyaoPublishBaseUrl(endpoint(storeConfig)) + rongyaoUpdateLanguageInfoEndpoint() + "?appId=" + appId;
        String responseBody = executeStoreRequest(
                trace(storeConfig, "update rongyao language info", "POST", url, Map.of("appId", appId), body),
                () -> restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockRongyaoSuccessResponse())
        );
        Map<String, Object> response = readJson(responseBody);
        ensureRongyaoSuccess(response, "update language info");
        return response;
    }

    private Map<String, Object> buildRongyaoSubmitBody(AppVersion version, AppReleaseRecord record, Path metadataPathCandidate) {
        ProjectMetadataContext metadataContext = resolveProjectMetadataContext(
                metadataPathCandidate == null ? firstNonBlank(version.getPackageUrl64(), version.getPackageUrl32(), version.getPackageUrl()) : metadataPathCandidate.toString()
        );
        Map<String, Object> metadata = metadataContext.metadata();
        AppInfo appInfo = version.getAppInfo();

        Map<String, Object> body = new LinkedHashMap<>();
        Integer forceUpdate = firstInteger(
                metadataLookup(metadata, "rongyao", "forceUpdate"),
                metadataLookup(metadata, null, "rongyaoForceUpdate")
        );
        if (forceUpdate != null) {
            body.put("forceUpdate", forceUpdate);
        }
        putIfHasText(
                body,
                "testAccount",
                firstNonBlank(
                        stringValue(metadataLookup(metadata, "rongyao", "testAccount")),
                        stringValue(metadataLookup(metadata, null, "rongyaoTestAccount"))
                )
        );
        putIfHasText(
                body,
                "testPassword",
                firstNonBlank(
                        stringValue(metadataLookup(metadata, "rongyao", "testPassword")),
                        stringValue(metadataLookup(metadata, null, "rongyaoTestPassword"))
                )
        );
        putIfHasText(
                body,
                "testComment",
                firstNonBlank(
                        stringValue(metadataLookup(metadata, "rongyao", "testComment")),
                        stringValue(metadataLookup(metadata, null, "rongyaoTestComment"))
                )
        );

        int releaseType = isStagedRelease(record) ? 3 : 1;
        body.put("releaseType", releaseType);
        if (!isStagedRelease(record)) {
            return body;
        }

        Map<String, Object> phasedReleaseInfo = new LinkedHashMap<>();
        phasedReleaseInfo.put("releasePercentage", formatRongyaoPercent(record.getGrayPercent()));
        phasedReleaseInfo.put("releaseStartDate", formatRongyaoDateTime(record.getGrayStartTime()));
        phasedReleaseInfo.put("releaseEndDate", formatRongyaoDateTime(record.getGrayEndTime()));
        phasedReleaseInfo.put(
                "releaseNote",
                firstNonBlank(
                        stringValue(metadataLookup(metadata, "rongyao", "releaseNote")),
                        stringValue(metadataLookup(metadata, null, "rongyaoReleaseNote")),
                        normalizeStageText(
                                10,
                                500,
                                appInfo == null ? null : appInfo.getAppDescription(),
                                appInfo == null ? null : appInfo.getAppName(),
                                appInfo == null ? null : appInfo.getPackageName(),
                                "Rongyao phased release note"
                        )
                )
        );
        body.put("phasedReleaseInfo", phasedReleaseInfo);
        return body;
    }

    private Map<String, Object> submitRongyaoAudit(AppStoreConfig storeConfig, String token, String appId, Map<String, Object> body) {
        String url = rongyaoPublishBaseUrl(endpoint(storeConfig)) + rongyaoSubmitAuditEndpoint() + "?appId=" + appId;
        String responseBody = executeStoreRequest(
                trace(storeConfig, "submit rongyao audit", "POST", url, Map.of("appId", appId), body),
                () -> restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockRongyaoSubmitAuditResponse())
        );
        Map<String, Object> response = readJson(responseBody);
        ensureRongyaoSuccess(response, "submit audit");
        return response;
    }

    private Map<String, Object> queryRongyaoAuditResult(AppStoreConfig storeConfig, String token, String appId, String releaseId) {
        String url = rongyaoPublishBaseUrl(endpoint(storeConfig)) + rongyaoAuditResultEndpoint();
        Map<String, Object> body = Map.of(
                "appId",
                List.of(Map.of(
                        "appId", toNumericIfPossible(appId),
                        "releaseId", releaseId
                ))
        );
        String responseBody = executeStoreRequest(
                trace(storeConfig, "query rongyao audit result", "POST", url, null, body),
                () -> restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockRongyaoAuditResultResponse(releaseId))
        );
        Map<String, Object> response = readJson(responseBody);
        ensureRongyaoSuccess(response, "query audit result");
        return response;
    }

    private Map<String, Object> queryRongyaoCurrentRelease(AppStoreConfig storeConfig, String token, String appId) {
        String url = rongyaoPublishBaseUrl(endpoint(storeConfig)) + rongyaoCurrentReleaseEndpoint() + "?appId=" + appId;
        String responseBody = executeStoreRequest(
                trace(storeConfig, "query rongyao current release", "GET", url, Map.of("appId", appId), Map.of("Authorization", "Bearer " + token)),
                () -> restClient.get()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockRongyaoCurrentReleaseResponse())
        );
        Map<String, Object> response = readJson(responseBody);
        ensureRongyaoSuccessOrBarePayload(response, "query current release", "releaseId");
        return response;
    }

    private Map<String, Object> queryRongyaoPhasedReleaseInfo(AppStoreConfig storeConfig, String token, String appId) {
        String url = rongyaoPublishBaseUrl(endpoint(storeConfig)) + rongyaoPhasedReleaseInfoEndpoint() + "?appId=" + appId;
        String responseBody = executeStoreRequest(
                trace(storeConfig, "query rongyao phased release info", "GET", url, Map.of("appId", appId), Map.of("Authorization", "Bearer " + token)),
                () -> restClient.get()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .body(String.class),
                () -> writeJson(mockRongyaoPhasedReleaseInfoResponse())
        );
        Map<String, Object> response = readJson(responseBody);
        ensureRongyaoSuccessOrBarePayload(response, "query phased release info", "releaseStatus");
        return response;
    }

    private Map<String, Object> findRongyaoAuditResult(Map<String, Object> response, String releaseId) {
        Object dataValue = response.get("data");
        if (dataValue instanceof List<?> dataList) {
            for (Object item : dataList) {
                Map<String, Object> auditResult = asMap(item);
                if (!StringUtils.hasText(releaseId) || releaseId.equals(firstString(auditResult, "releaseId"))) {
                    return auditResult;
                }
            }
        }
        return Map.of();
    }

    private Map<String, Object> extractRongyaoDataMap(Map<String, Object> response) {
        Object data = response.get("data");
        if (data instanceof Map<?, ?> map) {
            return asMap(data);
        }
        if (data == null && (response.containsKey("releaseId") || response.containsKey("releaseStatus"))) {
            return response;
        }
        return Map.of();
    }

    private ReleaseStatus mapRongyaoAuditResult(int auditResult, boolean stagedRelease) {
        return switch (auditResult) {
            case 1 -> ReleaseStatus.PASS;
            case 2 -> ReleaseStatus.REJECT;
            case 0, 4 -> ReleaseStatus.AUDITING;
            case 3 -> stagedRelease ? ReleaseStatus.PASS : ReleaseStatus.AUDITING;
            default -> ReleaseStatus.AUDITING;
        };
    }

    private ReleaseStatus mapRongyaoPhasedReleaseStatus(Map<String, Object> phasedReleaseInfo) {
        int releaseStatus = intValue(phasedReleaseInfo.get("releaseStatus"));
        return switch (releaseStatus) {
            case 1, 2, 4 -> ReleaseStatus.PASS;
            case 3, 5 -> ReleaseStatus.OFFLINE;
            default -> ReleaseStatus.AUDITING;
        };
    }

    private Map<String, Object> mockRongyaoTokenResponse() {
        return Map.of(
                "access_token", "mock-rongyao-" + UUID.randomUUID(),
                "expires_in", 3600
        );
    }

    private Map<String, Object> mockRongyaoAppIdResponse(String packageName) {
        return Map.of(
                "code", 0,
                "msg", "success",
                "data", List.of(Map.of(
                        "appId", "mock-rongyao-appid",
                        "pkgName", packageName
                ))
        );
    }

    private Map<String, Object> mockRongyaoFileUploadUrlResponse(List<Path> packagePaths) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (int index = 0; index < packagePaths.size(); index++) {
            Path packagePath = packagePaths.get(index);
            data.add(Map.of(
                    "fileName", packagePath.getFileName().toString(),
                    "objectId", String.valueOf(index + 1),
                    "uploadUrl", "https://mock.rongyao.local/upload/" + packagePath.getFileName()
            ));
        }
        return Map.of(
                "code", 0,
                "msg", "success",
                "data", data
        );
    }

    private Map<String, Object> mockRongyaoAppDetailResponse() {
        Map<String, Object> basicInfo = new LinkedHashMap<>();
        basicInfo.put("appCategoryId", 2);
        basicInfo.put("createTime", "2026-03-11T09:18:00+0800");
        basicInfo.put("packageName", "com.demo.rongyao");
        basicInfo.put("appId", 123456);
        basicInfo.put("privacyPolicyUrl", "https://mock.rongyao.local/privacy");

        List<Map<String, Object>> languageInfo = List.of(
                Map.of(
                        "languageId", "zh-CN",
                        "appName", "Mock Rongyao App",
                        "intro", "Mock Rongyao intro zh",
                        "briefIntro", "Mock Rongyao brief intro zh",
                        "newFeature", "Mock Rongyao new feature zh"
                ),
                Map.of(
                        "languageId", "en-US",
                        "appName", "Mock Rongyao App",
                        "intro", "Mock Rongyao intro en",
                        "briefIntro", "Mock Rongyao brief intro en",
                        "newFeature", "Mock Rongyao new feature en"
                )
        );

        Map<String, Object> publishInfo = new LinkedHashMap<>();
        publishInfo.put("releaseType", 1);
        publishInfo.put("releaseTime", "2026-03-11T10:00:00+0800");
        publishInfo.put("testAccount", "mock-test-account");
        publishInfo.put("testPassword", "mock-test-password");
        publishInfo.put("testComment", "mock test comment");

        List<Map<String, Object>> fileInfo = List.of(
                Map.of(
                        "objectId", 9002,
                        "fileType", RONGYAO_PACKAGE_FILE_TYPE,
                        "fileName", "mock-64.apk"
                )
        );

        Map<String, Object> releaseInfo = new LinkedHashMap<>();
        releaseInfo.put("versionName", "1.0.0");
        releaseInfo.put("versionCode", 100);
        releaseInfo.put("releaseStatus", 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("basicInfo", basicInfo);
        data.put("languageInfo", languageInfo);
        data.put("publishInfo", publishInfo);
        data.put("fileInfo", fileInfo);
        data.put("releaseInfo", releaseInfo);

        return Map.of(
                "code", 0,
                "msg", "success",
                "data", data
        );
    }

    private Map<String, Object> mockRongyaoUploadFileResponse(Path packagePath, String objectId) {
        return Map.of(
                "code", 0,
                "msg", "success",
                "data", Map.of(
                        "objectId", objectId,
                        "fileName", packagePath.getFileName().toString()
                )
        );
    }

    private Map<String, Object> mockRongyaoSuccessResponse() {
        return Map.of(
                "code", 0,
                "msg", "success"
        );
    }

    private Map<String, Object> mockRongyaoSubmitAuditResponse() {
        return Map.of(
                "code", 0,
                "msg", "success",
                "data", "mock-rongyao-" + UUID.randomUUID()
        );
    }

    private Map<String, Object> mockRongyaoAuditResultResponse(String releaseId) {
        return Map.of(
                "code", 0,
                "msg", "success",
                "data", List.of(Map.of(
                        "releaseId", releaseId,
                        "auditResult", 1
                ))
        );
    }

    private Map<String, Object> mockRongyaoCurrentReleaseResponse() {
        return Map.of(
                "releaseId", "mock-rongyao-current",
                "auditResult", 1
        );
    }

    private Map<String, Object> mockRongyaoPhasedReleaseInfoResponse() {
        return Map.of(
                "releaseStatus", 1
        );
    }

    private void ensureRongyaoSuccess(Map<String, Object> response, String action) {
        int code = intValue(response.get("code"));
        if (code == 0) {
            return;
        }
        String message = firstString(response, "msg", "message");
        throw new StoreApiException(
                HttpStatus.BAD_GATEWAY,
                "Rongyao " + action + " failed: code=" + code + ", msg=" + (StringUtils.hasText(message) ? message : "unknown error")
        );
    }

    private void ensureRongyaoSuccessOrBarePayload(Map<String, Object> response, String action, String barePayloadField) {
        if (response.containsKey("code")) {
            ensureRongyaoSuccess(response, action);
            return;
        }
        if (StringUtils.hasText(firstString(response, barePayloadField))) {
            return;
        }
        throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Rongyao " + action + " failed: unexpected response");
    }

    private String rongyaoPublishBaseUrl(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getBaseUrl()) ? endpoint.getBaseUrl() : RONGYAO_PUBLISH_BASE_URL;
    }

    private String rongyaoTokenUrl(StoreApiProperties.StoreEndpointProperties endpoint) {
        String baseUrl = StringUtils.hasText(endpoint.getBaseUrl()) ? endpoint.getBaseUrl() : RONGYAO_IAM_BASE_URL;
        String tokenEndpoint = StringUtils.hasText(endpoint.getTokenEndpoint()) ? endpoint.getTokenEndpoint() : RONGYAO_TOKEN_ENDPOINT;
        return baseUrl + tokenEndpoint;
    }

    private String rongyaoAppIdEndpoint() {
        return RONGYAO_APP_ID_ENDPOINT;
    }

    private String rongyaoAppDetailEndpoint() {
        return RONGYAO_APP_DETAIL_ENDPOINT;
    }

    private String rongyaoFileUploadUrlEndpoint() {
        return RONGYAO_FILE_UPLOAD_URL_ENDPOINT;
    }

    private String rongyaoFileUploadEndpoint() {
        return RONGYAO_FILE_UPLOAD_ENDPOINT;
    }

    private String rongyaoUpdateFileInfoEndpoint() {
        return RONGYAO_UPDATE_FILE_INFO_ENDPOINT;
    }

    private String rongyaoUpdateLanguageInfoEndpoint() {
        return RONGYAO_UPDATE_LANGUAGE_INFO_ENDPOINT;
    }

    private String rongyaoSubmitAuditEndpoint() {
        return RONGYAO_SUBMIT_AUDIT_ENDPOINT;
    }

    private String rongyaoAuditResultEndpoint() {
        return RONGYAO_AUDIT_RESULT_ENDPOINT;
    }

    private String rongyaoCurrentReleaseEndpoint() {
        return RONGYAO_CURRENT_RELEASE_ENDPOINT;
    }

    private String rongyaoPhasedReleaseInfoEndpoint() {
        return RONGYAO_PHASED_RELEASE_INFO_ENDPOINT;
    }

    private String formatRongyaoDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(RONGYAO_ZONE_ID).format(RONGYAO_DATE_TIME_FORMATTER);
    }

    private String formatRongyaoPercent(Number percent) {
        double value = percent == null ? 0D : percent.doubleValue();
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private Object toNumericIfPossible(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return value;
        }
        String text = String.valueOf(value);
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return text;
        }
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
