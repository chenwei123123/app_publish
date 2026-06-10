package com.app.publishservice.service;

import com.app.publishservice.common.exception.StoreApiException;
import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.domain.entity.AppInfo;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
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

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class XiaomiStorePlatformPublisher extends AbstractStorePlatformPublisher implements StorePlatformPublisher {

    private static final String XIAOMI_BASE_URL = "https://api.developer.xiaomi.com/devupload";
    private static final String XIAOMI_QUERY_ENDPOINT = "/dev/query";
    private static final String XIAOMI_SUBMIT_ENDPOINT = "/dev/push";

    XiaomiStorePlatformPublisher(
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
                && "xiaomi".equalsIgnoreCase(storeConfig.getStoreType().getCode());
    }

    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        String marker = StringUtils.hasText(storeConfig.getEmail()) ? storeConfig.getEmail() : "xiaomi-static";
        return new TokenPayload(TokenType.STATIC.getCode(), marker, LocalDateTime.now().plusYears(10));
    }

    @Override
    public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
            return submitXiaomiRelease(storeConfig, version);
        }
    }

    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        return super.queryReview(storeConfig, record, token);
    }

    private StoreSubmitResult submitXiaomiRelease(AppStoreConfig storeConfig, AppVersion version) {
        AppInfo appInfo = version.getAppInfo();
        if (appInfo == null || !StringUtils.hasText(appInfo.getAppName())) {
            throw new IllegalArgumentException("Xiaomi submit requires app name");
        }
        if (!StringUtils.hasText(appInfo.getPackageName())) {
            throw new IllegalArgumentException("Xiaomi submit requires app packageName");
        }

        String userName = resolveXiaomiUserName(storeConfig);
        String packageLocation = firstNonBlank(version.getPackageUrl64(), version.getPackageUrl());
        Path apkPath = requireLocalPackage(packageLocation, "Xiaomi package path is empty");
        ProjectMetadataContext metadataContext = resolveProjectMetadataContext(packageLocation);
        XiaomiPackageQueryResult packageQuery = queryXiaomiPackage(storeConfig, appInfo.getPackageName(), userName);
        XiaomiSubmitContext submitContext = resolveXiaomiSubmitContext(version, metadataContext, packageQuery, apkPath);

        Map<String, Object> requestData = buildXiaomiSubmitRequestData(userName, submitContext);
        String requestDataJson = writeJson(requestData);
        LinkedHashMap<String, Path> fileParts = buildXiaomiFileParts(submitContext);
        String sig = signXiaomiPayload(storeConfig, requestDataJson, fileParts);

        Map<String, Object> pushRequestLog = new LinkedHashMap<>();
        pushRequestLog.put("RequestData", requestData);
        pushRequestLog.put("SIG", sig);
        Map<String, Object> requestFiles = new LinkedHashMap<>();
        fileParts.forEach((key, value) -> requestFiles.put(key, value.getFileName().toString()));
        pushRequestLog.put("files", requestFiles);
        Map<String, Object> submitResponse = pushXiaomiPackage(storeConfig, requestDataJson, sig, fileParts, pushRequestLog);

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("packageQuery", readJson(packageQuery.requestLog()));
        requestLog.put("submit", pushRequestLog);
        Map<String, Object> responseLog = new LinkedHashMap<>();
        responseLog.put("packageQuery", readJson(packageQuery.responseLog()));
        responseLog.put("submit", submitResponse);

        String storeReleaseId = appInfo.getPackageName() + ":" + version.getVersionCode();
        String message = firstNonBlank(firstString(submitResponse, "message", "msg"), "submit success");
        return new StoreSubmitResult(storeReleaseId, writeJson(requestLog), writeJson(responseLog), message);
    }

    private XiaomiPackageQueryResult queryXiaomiPackage(AppStoreConfig storeConfig, String packageName, String userName) {
        Map<String, Object> requestData = new LinkedHashMap<>();
        requestData.put("packageName", packageName);
        requestData.put("userName", userName);
        String requestDataJson = writeJson(requestData);
        String sig = signXiaomiPayload(storeConfig, requestDataJson, Map.of());

        Map<String, Object> requestLog = new LinkedHashMap<>();
        requestLog.put("RequestData", requestData);
        requestLog.put("SIG", sig);

        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            Map<String, Object> mockResponse = new LinkedHashMap<>();
            mockResponse.put("result", 0);
            mockResponse.put("message", "mock query success");
            mockResponse.put("create", false);
            mockResponse.put("updateVersion", true);
            mockResponse.put("updateInfo", true);
            mockResponse.put("packageInfo", Map.of("packageName", packageName));
            return new XiaomiPackageQueryResult(
                    true,
                    false,
                    true,
                    writeJson(requestLog),
                    writeJson(mockResponse)
            );
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("RequestData", requestDataJson);
        body.add("SIG", sig);

        String url = xiaomiBaseUrl(endpoint) + xiaomiQueryEndpoint(endpoint);
        String responseBody = executeStoreRequest(
                trace(storeConfig, "query xiaomi package", "POST", url, null, requestLog),
                () -> restClient.post()
                        .uri(url)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        ensureXiaomiSuccess(response, "query package");
        Map<String, Object> packageInfo = asMap(response.get("packageInfo"));
        return new XiaomiPackageQueryResult(
                !packageInfo.isEmpty(),
                booleanValue(response.get("create"), packageInfo.isEmpty()),
                booleanValue(response.get("updateVersion"), !packageInfo.isEmpty()),
                writeJson(requestLog),
                responseBody
        );
    }

    private Map<String, Object> pushXiaomiPackage(
            AppStoreConfig storeConfig,
            String requestDataJson,
            String sig,
            Map<String, Path> fileParts,
            Map<String, Object> requestLog
    ) {
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (endpoint.isMockEnabled()) {
            return Map.of("result", 0, "message", "mock submit success");
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("RequestData", requestDataJson);
        body.add("SIG", sig);
        for (Map.Entry<String, Path> entry : fileParts.entrySet()) {
            body.add(entry.getKey(), buildMultipartFilePart(entry.getKey(), entry.getValue()));
        }

        String url = xiaomiBaseUrl(endpoint) + xiaomiSubmitEndpoint(endpoint);
        String responseBody = executeStoreRequest(
                trace(storeConfig, "submit xiaomi release", "POST", url, null, requestLog),
                () -> restClient.post()
                        .uri(url)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .retrieve()
                        .body(String.class)
        );
        Map<String, Object> response = readJson(responseBody);
        ensureXiaomiSuccess(response, "submit release");
        return response;
    }

    private Map<String, Object> buildXiaomiSubmitRequestData(String userName, XiaomiSubmitContext submitContext) {
        Map<String, Object> requestData = new LinkedHashMap<>();
        requestData.put("userName", userName);
        requestData.put("synchroType", submitContext.synchroType());
        requestData.put("appInfo", submitContext.appInfoPayload());
        return requestData;
    }

    private LinkedHashMap<String, Path> buildXiaomiFileParts(XiaomiSubmitContext submitContext) {
        LinkedHashMap<String, Path> fileParts = new LinkedHashMap<>();
        fileParts.put("apk", submitContext.apkPath());
        if (submitContext.secondApkPath() != null) {
            fileParts.put("secondApk", submitContext.secondApkPath());
        }
        fileParts.put("icon", submitContext.iconPath());
        for (int index = 0; index < submitContext.screenshotPaths().size(); index++) {
            fileParts.put("screenshot_" + (index + 1), submitContext.screenshotPaths().get(index));
        }
        for (int index = 0; index < submitContext.padScreenshotPaths().size(); index++) {
            fileParts.put("screenshot_pad_" + (index + 1), submitContext.padScreenshotPaths().get(index));
        }
        return fileParts;
    }

    private XiaomiSubmitContext resolveXiaomiSubmitContext(
            AppVersion version,
            ProjectMetadataContext metadataContext,
            XiaomiPackageQueryResult packageQuery,
            Path apkPath
    ) {
        AppInfo appInfo = version.getAppInfo();
        Map<String, Object> metadata = metadataContext.metadata();
        boolean create = !packageQuery.packageExists() && packageQuery.createAllowed();
        if (!create && !packageQuery.updateVersionAllowed()) {
            throw new IllegalStateException("Xiaomi package query indicates version update is not allowed for " + appInfo.getPackageName());
        }

        Path secondApkPath = resolveXiaomiSecondApkPath(metadataContext, metadata, version, apkPath);
        Path iconPath = resolveXiaomiIconPath(metadataContext, metadata);
        if (iconPath == null) {
            throw new IllegalStateException("Xiaomi submit requires icon asset. Provide xiaomi.iconPath in project app-publish-metadata.json.");
        }

        Integer suitableTypeValue = firstInteger(
                metadataLookup(metadata, "xiaomi", "suitableType"),
                metadataLookup(metadata, null, "xiaomiSuitableType")
        );
        int suitableType = suitableTypeValue == null ? 0 : suitableTypeValue;

        List<Path> screenshotPaths = create ? resolveXiaomiScreenshotPaths(metadataContext, metadata) : List.of();
        if (create) {
            validateXiaomiScreenshotCount(
                    screenshotPaths.size(),
                    3,
                    5,
                    "Xiaomi app create requires 3 to 5 phone screenshots. Configure xiaomi.screenshotPaths in project app-publish-metadata.json."
            );
        }
        List<Path> padScreenshotPaths = create && (suitableType == 1 || suitableType == 2)
                ? resolveXiaomiPadScreenshotPaths(metadataContext, metadata)
                : List.of();
        if (create && (suitableType == 1 || suitableType == 2)) {
            validateXiaomiScreenshotCount(
                    padScreenshotPaths.size(),
                    4,
                    5,
                    "Xiaomi app create for pad devices requires 4 to 5 pad screenshots. Configure xiaomi.padScreenshotPaths in project app-publish-metadata.json."
            );
        }

        Map<String, Object> appInfoPayload = new LinkedHashMap<>();
        appInfoPayload.put("appName", appInfo.getAppName().trim());
        appInfoPayload.put("packageName", appInfo.getPackageName().trim());
        addIfHasText(appInfoPayload, "publisherName", firstNonBlank(
                stringValue(metadataLookup(metadata, "xiaomi", "publisherName")),
                stringValue(metadataLookup(metadata, null, "xiaomiPublisherName"))
        ));
        addIfHasText(appInfoPayload, "versionName", version.getVersionName());
        addIfNotNull(appInfoPayload, "category", create ? requireXiaomiCategory(metadata) : firstInteger(
                metadataLookup(metadata, "xiaomi", "category"),
                metadataLookup(metadata, "xiaomi", "categoryId"),
                metadataLookup(metadata, null, "xiaomiCategory")
        ));
        addIfHasText(appInfoPayload, "keyWords", create
                ? requireText(firstNonBlank(
                stringValue(metadataLookup(metadata, "xiaomi", "keyWords")),
                stringValue(metadataLookup(metadata, "xiaomi", "keywords")),
                stringValue(metadataLookup(metadata, null, "xiaomiKeywords"))
        ), "Xiaomi app create requires xiaomi.keyWords in project app-publish-metadata.json.")
                : firstNonBlank(
                stringValue(metadataLookup(metadata, "xiaomi", "keyWords")),
                stringValue(metadataLookup(metadata, "xiaomi", "keywords")),
                stringValue(metadataLookup(metadata, null, "xiaomiKeywords"))
        ));
        addIfHasText(appInfoPayload, "desc", create
                ? requireText(firstNonBlank(
                stringValue(metadataLookup(metadata, "xiaomi", "desc")),
                appInfo.getAppDescription()
        ), "Xiaomi app create requires xiaomi.desc or appDescription.")
                : firstNonBlank(
                stringValue(metadataLookup(metadata, "xiaomi", "desc")),
                appInfo.getAppDescription()
        ));
        addIfHasText(appInfoPayload, "updateDesc", firstNonBlank(
                version.getUpdateLog(),
                stringValue(metadataLookup(metadata, "xiaomi", "updateDesc")),
                appInfo.getAppDescription(),
                appInfo.getAppName()
        ));
        addIfHasText(appInfoPayload, "brief", create
                ? requireText(firstNonBlank(
                stringValue(metadataLookup(metadata, "xiaomi", "brief")),
                appInfo.getAppName(),
                appInfo.getAppDescription()
        ), "Xiaomi app create requires xiaomi.brief or appName.")
                : firstNonBlank(
                stringValue(metadataLookup(metadata, "xiaomi", "brief")),
                appInfo.getAppName(),
                appInfo.getAppDescription()
        ));
        addIfHasText(appInfoPayload, "privacyUrl", requireText(firstNonBlank(
                stringValue(metadataLookup(metadata, "xiaomi", "privacyUrl")),
                appInfo.getPrivacyUrl()
        ), "Xiaomi submit requires privacyUrl. Provide app privacyUrl or xiaomi.privacyUrl."));
        addIfHasText(appInfoPayload, "testAccount", normalizeXiaomiJsonString(metadataLookup(metadata, "xiaomi", "testAccount")));
        addIfNotNull(appInfoPayload, "onlineTime", firstLong(
                metadataLookup(metadata, "xiaomi", "onlineTime"),
                metadataLookup(metadata, null, "xiaomiOnlineTime")
        ));
        addIfNotNull(appInfoPayload, "suitableType", suitableTypeValue);

        return new XiaomiSubmitContext(
                create ? 0 : 1,
                apkPath,
                secondApkPath,
                iconPath,
                screenshotPaths,
                padScreenshotPaths,
                appInfoPayload
        );
    }

    private String resolveXiaomiUserName(AppStoreConfig storeConfig) {
        String userName = firstNonBlank(storeConfig.getEmail(), storeConfig.getAccountName());
        if (!StringUtils.hasText(userName)) {
            throw new IllegalArgumentException("Xiaomi submit requires store config email or accountName as userName");
        }
        return userName.trim();
    }

    private String signXiaomiPayload(AppStoreConfig storeConfig, String requestDataJson, Map<String, Path> fileParts) {
        if (!StringUtils.hasText(storeConfig.getPrivateKey())) {
            throw new IllegalArgumentException("Xiaomi submit requires privateKey as access password");
        }
        if (!StringUtils.hasText(storeConfig.getPublicKey())) {
            throw new IllegalArgumentException("Xiaomi submit requires publicKey");
        }

        List<Map<String, Object>> sigEntries = new ArrayList<>();
        sigEntries.add(xiaomiSigEntry("RequestData", md5Hex(requestDataJson.getBytes(StandardCharsets.UTF_8))));
        if (fileParts != null) {
            for (Map.Entry<String, Path> entry : fileParts.entrySet()) {
                sigEntries.add(xiaomiSigEntry(entry.getKey(), md5Hex(entry.getValue())));
            }
        }

        Map<String, Object> sigPayload = new LinkedHashMap<>();
        sigPayload.put("sig", sigEntries);
        sigPayload.put("password", storeConfig.getPrivateKey());
        return encryptXiaomiSig(writeJson(sigPayload), storeConfig.getPublicKey());
    }

    private Map<String, Object> xiaomiSigEntry(String name, String hash) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("hash", hash);
        return entry;
    }

    private HttpEntity<FileSystemResource> buildMultipartFilePart(String partName, Path filePath) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        fileHeaders.setContentDispositionFormData(partName, filePath.getFileName().toString());
        return new HttpEntity<>(new FileSystemResource(filePath), fileHeaders);
    }

    private String encryptXiaomiSig(String plainText, String publicKeyValue) {
        try {
            PublicKey publicKey = parseXiaomiPublicKey(publicKeyValue);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            int keySizeBytes = (((RSAKey) publicKey).getModulus().bitLength() + 7) / 8;
            int maxBlockSize = keySizeBytes - 11;
            byte[] source = plainText.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            for (int offset = 0; offset < source.length; offset += maxBlockSize) {
                int length = Math.min(maxBlockSize, source.length - offset);
                outputStream.write(cipher.doFinal(source, offset, length));
            }
            return toHex(outputStream.toByteArray());
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("Failed to sign Xiaomi request", ex);
        }
    }

    private PublicKey parseXiaomiPublicKey(String publicKeyValue) throws GeneralSecurityException {
        String normalized = publicKeyValue
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(normalized);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePublic(keySpec);
    }

    private Path resolveXiaomiIconPath(ProjectMetadataContext metadataContext, Map<String, Object> metadata) {
        Object metadataValue = metadataLookup(metadata, "xiaomi", "iconPath");
        if (metadataValue == null) {
            metadataValue = metadataLookup(metadata, null, "xiaomiIconPath");
        }
        return resolveProjectAssetPath(metadataContext.metadataPath(), metadataValue);
    }

    private List<Path> resolveXiaomiScreenshotPaths(ProjectMetadataContext metadataContext, Map<String, Object> metadata) {
        return resolveProjectAssetPaths(
                metadataContext.metadataPath(),
                firstList(
                        metadataLookup(metadata, "xiaomi", "screenshotPaths"),
                        metadataLookup(metadata, "xiaomi", "screenshots"),
                        metadataLookup(metadata, null, "xiaomiScreenshotPaths")
                ),
                "Xiaomi asset file not found in project: "
        );
    }

    private List<Path> resolveXiaomiPadScreenshotPaths(ProjectMetadataContext metadataContext, Map<String, Object> metadata) {
        return resolveProjectAssetPaths(
                metadataContext.metadataPath(),
                firstList(
                        metadataLookup(metadata, "xiaomi", "padScreenshotPaths"),
                        metadataLookup(metadata, "xiaomi", "screenshotPadPaths"),
                        metadataLookup(metadata, "xiaomi", "padScreenshots"),
                        metadataLookup(metadata, null, "xiaomiPadScreenshotPaths")
                ),
                "Xiaomi asset file not found in project: "
        );
    }

    private Path resolveXiaomiSecondApkPath(
            ProjectMetadataContext metadataContext,
            Map<String, Object> metadata,
            AppVersion version,
            Path apkPath
    ) {
        Object metadataValue = firstNonNull(
                metadataLookup(metadata, "xiaomi", "secondApkPath"),
                metadataLookup(metadata, "xiaomi", "secondApk"),
                metadataLookup(metadata, "xiaomi", "apk32Path"),
                metadataLookup(metadata, null, "xiaomiSecondApkPath")
        );
        Path resolvedPath = resolveProjectAssetPath(metadataContext.metadataPath(), metadataValue);
        if (resolvedPath != null) {
            return resolvedPath;
        }
        String packageUrl32 = version.getPackageUrl32();
        if (StringUtils.hasText(packageUrl32)) {
            Path secondApkPath = requireLocalPackage(packageUrl32, "Xiaomi second apk path is empty");
            if (!secondApkPath.equals(apkPath)) {
                return secondApkPath;
            }
        }
        return null;
    }

    private Integer requireXiaomiCategory(Map<String, Object> metadata) {
        Integer category = firstInteger(
                metadataLookup(metadata, "xiaomi", "category"),
                metadataLookup(metadata, "xiaomi", "categoryId"),
                metadataLookup(metadata, null, "xiaomiCategory")
        );
        if (category == null) {
            throw new IllegalStateException("Xiaomi app create requires xiaomi.category in project app-publish-metadata.json.");
        }
        return category;
    }

    private String normalizeXiaomiJsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text) ? text.trim() : null;
        }
        return writeJson(value);
    }

    private void addIfHasText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private void addIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private void validateXiaomiScreenshotCount(int screenshotCount, int minCount, int maxCount, String message) {
        if (screenshotCount < minCount || screenshotCount > maxCount) {
            throw new IllegalStateException(message + " Current count=" + screenshotCount + ".");
        }
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.toString().trim();
        if ("true".equalsIgnoreCase(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized) || "0".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private Long firstLong(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            long parsed = longValue(value, Long.MIN_VALUE);
            if (parsed != Long.MIN_VALUE) {
                return parsed;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void ensureXiaomiSuccess(Map<String, Object> response, String action) {
        int result = intValue(response.get("result"));
        if (result == 0) {
            return;
        }
        String message = firstNonBlank(
                firstString(response, "message", "msg", "err_msg", "errMsg"),
                "unknown error"
        );
        throw new StoreApiException(HttpStatus.BAD_GATEWAY, "Xiaomi " + action + " failed: result=" + result + ", msg=" + message);
    }

    private String xiaomiBaseUrl(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getBaseUrl()) ? endpoint.getBaseUrl() : XIAOMI_BASE_URL;
    }

    private String xiaomiQueryEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getTokenEndpoint()) ? endpoint.getTokenEndpoint() : XIAOMI_QUERY_ENDPOINT;
    }

    private String xiaomiSubmitEndpoint(StoreApiProperties.StoreEndpointProperties endpoint) {
        return StringUtils.hasText(endpoint.getSubmitEndpoint()) ? endpoint.getSubmitEndpoint() : XIAOMI_SUBMIT_ENDPOINT;
    }

    private String md5Hex(byte[] bytes) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(bytes);
            return toHex(messageDigest.digest());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("MD5 algorithm is not available", ex);
        }
    }

    private String md5Hex(Path path) {
        return md5Hex(new PackageContentSource(path.getFileName().toString(), path, null));
    }

    protected String toHex(byte[] bytes) {
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

    private record XiaomiPackageQueryResult(
            boolean packageExists,
            boolean createAllowed,
            boolean updateVersionAllowed,
            String requestLog,
            String responseLog
    ) {
    }

    private record XiaomiSubmitContext(
            int synchroType,
            Path apkPath,
            Path secondApkPath,
            Path iconPath,
            List<Path> screenshotPaths,
            List<Path> padScreenshotPaths,
            Map<String, Object> appInfoPayload
    ) {
    }
}
