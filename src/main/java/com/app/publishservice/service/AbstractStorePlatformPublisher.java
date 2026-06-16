package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.common.exception.StoreApiException;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.ReleaseStatus;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

abstract class AbstractStorePlatformPublisher implements StorePublisher {

    protected static final Logger log = LoggerFactory.getLogger(AbstractStorePlatformPublisher.class);
    private static final DateTimeFormatter PACKAGE_CACHE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String CMS_ARTIFACT_HOST = "artifacts.cmschina.com.cn";

    protected final RestClient restClient;
    protected final ObjectMapper objectMapper;
    protected final AppProperties appProperties;
    protected final StoreRequestLogService storeRequestLogService;
    protected final HttpClient packageHttpClient;
    protected final PublishMetadataResolver publishMetadataResolver;

    /**
     * 初始化AbstractStorePlatformPublisher。
     */
    protected AbstractStorePlatformPublisher(RestClient restClient, ObjectMapper objectMapper, AppProperties appProperties) {
        this(restClient, objectMapper, appProperties, null);
    }

    /**
     * 初始化AbstractStorePlatformPublisher。
     */
    @Autowired
    protected AbstractStorePlatformPublisher(
            RestClient restClient,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            StoreRequestLogService storeRequestLogService
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.storeRequestLogService = storeRequestLogService;
        this.publishMetadataResolver = new PublishMetadataResolver(appProperties);
        this.packageHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(appProperties.getStoreApi().getDefaultTimeoutSeconds(), 1)))
                .build();
    }

    /**
     * 刷新令牌。
     */
    @Override
    public TokenPayload refreshToken(AppStoreConfig storeConfig) {
        log.debug("Start refresh token, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
        StoreApiProperties.StoreEndpointProperties endpoint = endpoint(storeConfig);
        if (StringUtils.hasText(storeConfig.getToken())) {
            log.debug("Use configured static token, storeConfigId={}, storeType={}", storeConfig.getId(), storeConfig.getStoreType().getCode());
            return new TokenPayload(TokenType.STATIC.getCode(), storeConfig.getToken(), LocalDateTime.now().plusYears(10));
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
        body.put("publicKey", storeConfig.getPublicKey());
        body.put("privateKey", storeConfig.getPrivateKey());
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

    /**
     * 提交发布。
     */
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

    /**
     * 查询审核。
     */
    @Override
    public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
        log.debug("Start query review, releaseId={}, storeType={}, releaseType={}", record.getId(), record.getStoreType(), record.getReleaseType());
        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record == null ? null : record.getId())) {
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

    /**
     * 处理接口地址相关逻辑。
     */
    protected StoreApiProperties.StoreEndpointProperties endpoint(AppStoreConfig storeConfig) {
        return appProperties.getStoreApi().getStore(storeConfig.getStoreType().getCode());
    }

    /**
     * 处理execute 商店请求相关逻辑。
     */
    protected <T> T executeStoreRequest(String action, Supplier<T> request) {
        return executeStoreRequest(null, action, request);
    }

    /**
     * 处理execute 商店请求相关逻辑。
     */
    protected <T> T executeStoreRequest(StoreRequestTrace trace, Supplier<T> request) {
        return executeStoreRequest(trace, trace == null ? null : trace.action(), request);
    }

    /**
     * 处理execute 商店请求相关逻辑。
     */
    protected <T> T executeStoreRequest(StoreRequestTrace trace, String action, Supplier<T> request) {
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
            String responseBody = decodeStoreResponseBody(action, ex.getResponseBodyAsByteArray());
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

    /**
     * 处理trace相关逻辑。
     */
    protected StoreRequestTrace trace(
            AppStoreConfig storeConfig,
            String action,
            String requestMethod,
            String requestUrl,
            Object requestParams,
            Object requestBody
    ) {
        return new StoreRequestTrace(storeConfig, action, requestMethod, requestUrl, requestParams, requestBody);
    }

    /**
     * 处理请求载荷相关逻辑。
     */
    protected Map<String, Object> requestPayload(Object body, Map<String, Object> extras) {
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

    /**
     * 处理日志商店请求 Success相关逻辑。
     */
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

    /**
     * 处理日志商店请求 Failure相关逻辑。
     */
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

    /**
     * 转换响应。
     */
    private String stringifyResponse(String action, Object response) {
        if (response == null) {
            return null;
        }
        if (response instanceof byte[] bytes) {
            return decodeStoreResponseBody(action, bytes);
        }
        if (response instanceof Map<?, ?> map) {
            return writeJson(map);
        }
        if (response instanceof Path path) {
            return path.toString();
        }
        return String.valueOf(response);
    }

    /**
     * 转换Resource Access 异常。
     */
    private StoreApiException translateResourceAccessException(String action, ResourceAccessException ex) {
        boolean timeout = isTimeoutException(ex);
        HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        String message = timeout
                ? "Store API request timed out during " + action
                : "Store API is unavailable during " + action + ": " + ex.getMessage();
        log.warn("Store API resource access failed during {}, timeout={}", action, timeout, ex);
        return new StoreApiException(status, message, ex);
    }

    /**
     * 判断是否超时异常。
     */
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

    /**
     * 构建响应报文 Suffix。
     */
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

    /**
     * 映射状态。
     */
    protected ReleaseStatus mapStatus(String value) {
        return switch (value.toLowerCase()) {
            case "pass", "approved", "online" -> ReleaseStatus.PASS;
            case "reject", "rejected", "failed" -> ReleaseStatus.REJECT;
            case "offline" -> ReleaseStatus.OFFLINE;
            default -> ReleaseStatus.AUDITING;
        };
    }

    /**
     * 获取首个字符串。
     */
    protected String firstString(Map<String, Object> source, String... keys) {
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

    /**
     * 写入JSON。
     */
    protected String writeJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            return String.valueOf(object);
        }
    }

    /**
     * 校验Local 包。
     */
    protected Path requireLocalPackage(AppVersion version) {
        return requireLocalPackage(version.getPackageUrl(), "Package path is empty");
    }

    /**
     * 校验Local 包。
     */
    protected Path requireLocalPackage(String packageLocation, String emptyMessage) {
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

    /**
     * 计算 MD5 摘要Hex。
     */
    private String md5Hex(Path path) {
        return md5Hex(new PackageContentSource(path.getFileName().toString(), path, null));
    }

    /**
     * 处理sha256 Hex相关逻辑。
     */
    protected String sha256Hex(Path path) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
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
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }

    /**
     * 计算 MD5 摘要Hex。
     */
    private String md5Hex(byte[] content) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(content);
            return toHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 algorithm is not available", ex);
        }
    }

    /**
     * 计算 MD5 摘要Hex。
     */
    protected String md5Hex(PackageContentSource packageSource) {
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

    /**
     * 处理hmac Sha256 Hex相关逻辑。
     */
    protected String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign request", ex);
        }
    }

    /**
     * 处理Hex相关逻辑。
     */
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

    /**
     * 处理文件 Size相关逻辑。
     */
    protected long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read file size: " + path, ex);
        }
    }

    /**
     * 解码商店响应报文。
     */
    protected String decodeStoreResponseBody(String action, byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * 解析项目元数据上下文。
     */
    protected ProjectMetadataContext resolveProjectMetadataContext(String packageLocation) {
        return new ProjectMetadataContext(
                publishMetadataResolver.metadataPath(),
                publishMetadataResolver.metadata()
        );
    }

    /**
     * 解析项目 Asset 路径。
     */
    protected Path resolveProjectAssetPath(Path metadataPath, Object assetLocation) {
        return publishMetadataResolver.resolveAssetPath(metadataPath, assetLocation);
    }

    /**
     * 解析项目 Asset 路径。
     */
    protected List<Path> resolveProjectAssetPaths(Path metadataPath, List<String> assetLocations) {
        return resolveProjectAssetPaths(metadataPath, assetLocations, "Asset file not found in project: ");
    }

    /**
     * 解析项目 Asset 路径。
     */
    protected List<Path> resolveProjectAssetPaths(Path metadataPath, List<String> assetLocations, String assetNotFoundMessagePrefix) {
        return publishMetadataResolver.resolveAssetPaths(metadataPath, assetLocations, assetNotFoundMessagePrefix);
    }

    /**
     * 处理元数据 Lookup相关逻辑。
     */
    protected Object metadataLookup(Map<String, Object> metadata, String sectionKey, String key) {
        return publishMetadataResolver.metadataLookup(metadata, sectionKey, key);
    }

    /**
     * 获取首个列表。
     */
    protected List<String> firstList(Object... values) {
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

    /**
     * 获取首个Integer。
     */
    protected Integer firstInteger(Object... values) {
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

    /**
     * 读取JSON。
     */
    protected Map<String, Object> readJson(String body) {
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

    /**
     * 读取JSON If Possible。
     */
    protected Map<String, Object> readJsonIfPossible(String body) {
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

    /**
     * 处理as 映射相关逻辑。
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /**
     * 处理int 值相关逻辑。
     */
    protected int intValue(Object value) {
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

    /**
     * 处理long 值相关逻辑。
     */
    protected long longValue(Object value, long defaultValue) {
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

    /**
     * 判断是否灰度发布。
     */
    protected boolean isStagedRelease(AppReleaseRecord record) {
        return record != null && record.getReleaseType() != null && record.getReleaseType() == 2L;
    }

    /**
     * 解析包下载 URL。
     */
    protected String resolvePackageDownloadUrl(String packageLocation, Path packagePath) {
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

    /**
     * 解析仓库 Relative 路径。
     */
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

    /**
     * 下载包 Local。
     */
    protected Path downloadPackageToLocal(String downloadUrl, String fileName) {
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

    /**
     * 打开包流。
     */
    private InputStream openPackageStream(PackageContentSource packageSource) throws IOException {
        if (packageSource.localPath() != null) {
            return Files.newInputStream(packageSource.localPath());
        }
        return openRemotePackageStream(packageSource.remoteUrl());
    }

    /**
     * 上传Resource。
     */
    protected Object uploadResource(PackageContentSource packageSource) {
        if (packageSource.localPath() != null) {
            return new FileSystemResource(packageSource.localPath());
        }
        return new RemotePackageResource(packageSource.fileName(), packageSource.remoteUrl());
    }

    /**
     * 打开Remote 包流。
     */
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

    /**
     * 应用包授权。
     */
    private void applyPackageAuthorization(HttpHeaders headers, String downloadUrl) {
        String authorization = resolvePackageAuthorization(downloadUrl);
        if (StringUtils.hasText(authorization)) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    /**
     * 解析包授权。
     */
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

    /**
     * 判断是否包仓库 URL。
     */
    private boolean isPackageRepositoryUrl(String downloadUrl) {
        String baseUrl = appProperties.getPackageRepository().getBaseUrl();
        return StringUtils.hasText(baseUrl) && downloadUrl.startsWith(baseUrl.trim());
    }

    /**
     * 判断是否Cms Artifact URL。
     */
    private boolean isCmsArtifactUrl(String downloadUrl) {
        try {
            return CMS_ARTIFACT_HOST.equalsIgnoreCase(URI.create(downloadUrl).getHost());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 分配Downloaded 包路径。
     */
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

    /**
     * 处理路径相关逻辑。
     */
    protected Path toPath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException ex) {
            return null;
        }
    }

    /**
     * 判断是否HTTP URL。
     */
    private boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    /**
     * 处理infer 文件名称相关逻辑。
     */
    protected String inferFileName(String packageLocation) {
        String normalized = packageLocation.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
    }

    /**
     * 规范化仓库路径。
     */
    private String normalizeRepositoryPath(String value) {
        return value.replace('\\', '/').replaceFirst("^/+", "");
    }

    /**
     * 构建查询字符串。
     */
    protected String buildQueryString(Map<String, ?> params) {
        List<String> pairs = new ArrayList<>();
        params.forEach((key, value) -> {
            if (value != null) {
                pairs.add(encodeQueryParam(key) + "=" + encodeQueryParam(String.valueOf(value)));
            }
        });
        return String.join("&", pairs);
    }

    /**
     * 编码查询参数。
     */
    private String encodeQueryParam(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 处理join URL相关逻辑。
     */
    private String joinUrl(String baseUrl, String relativePath) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedRelativePath = normalizeRepositoryPath(relativePath);
        return normalizedBaseUrl + "/" + normalizedRelativePath;
    }

    /**
     * 规范化Title。
     */
    protected String normalizeTitle(String appName, String packageName) {
        String title = firstNonBlank(appName, packageName, "应用发布");
        title = title.replaceAll("[\\r\\n\\t]", " ").trim();
        return title.length() <= 20 ? title : title.substring(0, 20);
    }

    /**
     * 规范化阶段文本。
     */
    protected String normalizeStageText(int minLength, int maxLength, String... candidates) {
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

    /**
     * 获取首个Non Blank。
     */
    protected String firstNonBlank(String... values) {
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

    /**
     * 获取首个Non Null。
     */
    protected Object firstNonNull(Object... values) {
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

    protected record ProjectMetadataContext(
            Path metadataPath,
            Map<String, Object> metadata
    ) {
    }

    protected record PackageContentSource(
            String fileName,
            Path localPath,
            String remoteUrl
    ) {
        /**
         * 处理source 类型相关逻辑。
         */
        String sourceType() {
            return localPath != null ? "local" : "remote";
        }
    }

    protected record StoreRequestTrace(
            AppStoreConfig storeConfig,
            String action,
            String requestMethod,
            String requestUrl,
            Object requestParams,
            Object requestBody
    ) {
    }

    private final class RemotePackageResource extends AbstractResource {

        private final String fileName;
        private final String downloadUrl;

        /**
         * 初始化RemotePackageResource。
         */
        private RemotePackageResource(String fileName, String downloadUrl) {
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
        }

        /**
         * 获取Description。
         */
        @Override
        public String getDescription() {
            return "remote package " + downloadUrl;
        }

        /**
         * 获取Filename。
         */
        @Override
        public String getFilename() {
            return fileName;
        }

        /**
         * 处理内容 Length相关逻辑。
         */
        @Override
        public long contentLength() {
            return -1;
        }

        /**
         * 获取Input 流。
         */
        @Override
        public InputStream getInputStream() throws IOException {
            return openRemotePackageStream(downloadUrl);
        }
    }
}

