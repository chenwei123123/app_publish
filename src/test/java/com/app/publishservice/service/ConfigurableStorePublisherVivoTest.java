package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.domain.entity.AppInfo;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.ReleaseStatus;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.service.model.StoreReviewResult;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurableStorePublisherVivoTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldUploadApkToVivoWithSignedMultipartRequest() throws Exception {
        byte[] apkBytes = "fake-apk-binary".getBytes(StandardCharsets.UTF_8);
        Path apk = tempDir.resolve("demo.apk");
        Files.write(apk, apkBytes);

        AtomicReference<Map<String, String>> requestParts = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> handleUpload(exchange, requestParts));
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(new ObjectMapper(), appProperties(server));
            StoreSubmitResult result = publisher.submitRelease(vivoStoreConfig(), appVersion(apk), new AppReleaseRecord(), "");

            assertEquals("serial-123", result.storeReleaseId());
            Map<String, String> parts = requestParts.get();
            assertEquals("app.upload.apk.app", parts.get("method"));
            assertEquals("demo-access-key", parts.get("access_key"));
            assertEquals("json", parts.get("format"));
            assertEquals("1.0", parts.get("v"));
            assertEquals("hmac", parts.get("sign_method"));
            assertEquals("developer", parts.get("target_app_key"));
            assertEquals("com.demo.app", parts.get("packageName"));
            assertEquals(md5Hex(apkBytes), parts.get("fileMd5"));
            assertEquals("demo.apk", parts.get("file.filename"));

            Map<String, String> signSource = new LinkedHashMap<>();
            signSource.put("access_key", parts.get("access_key"));
            signSource.put("fileMd5", parts.get("fileMd5"));
            signSource.put("format", parts.get("format"));
            signSource.put("method", parts.get("method"));
            signSource.put("packageName", parts.get("packageName"));
            signSource.put("sign_method", parts.get("sign_method"));
            signSource.put("target_app_key", parts.get("target_app_key"));
            signSource.put("timestamp", parts.get("timestamp"));
            signSource.put("v", parts.get("v"));
            assertEquals(sign(signSource, "demo-access-secret"), parts.get("sign"));
            assertTrue(result.responseLog().contains("serial-123"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSubmitVivoStageReleaseWithCreateUpdateRequest() throws Exception {
        byte[] apkBytes = "fake-stage-apk".getBytes(StandardCharsets.UTF_8);
        Path apk = tempDir.resolve("stage-demo.apk");
        Files.write(apk, apkBytes);

        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<Map<String, String>> uploadParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> stageForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> {
            int index = requestCount.incrementAndGet();
            if (index == 1) {
                handleUpload(exchange, uploadParts);
                return;
            }
            handleStageSubmit(exchange, stageForm);
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(new ObjectMapper(), appProperties(server));
            AppReleaseRecord record = stagedRecord();
            StoreSubmitResult result = publisher.submitRelease(vivoStoreConfig(), appVersion(apk), record, "");

            assertEquals("serial-123", result.storeReleaseId());
            assertEquals("1", uploadParts.get().get("stageType"));
            assertEquals("app.sync.create.update.stage.app", stageForm.get().get("method"));
            assertEquals("com.demo.app", stageForm.get().get("packageName"));
            assertEquals("0", stageForm.get().get("subPackage"));
            assertEquals("2026-06-01 10:00:00", stageForm.get().get("stagedStartTime"));
            assertEquals("2026-06-03 10:00:00", stageForm.get().get("stagedEndTime"));
            assertEquals("66", stageForm.get().get("stagedProportion"));
            assertEquals("serial-123", stageForm.get().get("apkUuid64"));
            assertEquals("DemoApp", stageForm.get().get("mainTitle"));
            assertTrue(result.requestLog().contains("stageSubmit"));
            assertTrue(result.responseLog().contains("stage submit success"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldQueryVivoStageDetails() throws Exception {
        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> handleStageDetailQuery(exchange, queryForm));
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(new ObjectMapper(), appProperties(server));
            AppReleaseRecord record = stagedRecord();
            record.setPackageName("com.demo.app");
            StoreReviewResult result = publisher.queryReview(vivoStoreConfig(), record, "");

            assertEquals(ReleaseStatus.PASS, result.releaseStatus());
            assertEquals("app.query.stage.details", queryForm.get().get("method"));
            assertEquals("com.demo.app", queryForm.get().get("packageName"));
            assertTrue(result.responseLog().contains("auditStatus"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectNonApkPackageForVivo() throws Exception {
        Path bundle = tempDir.resolve("demo.aab");
        Files.writeString(bundle, "fake bundle", StandardCharsets.UTF_8);

        ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(new ObjectMapper(), new AppProperties());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> publisher.submitRelease(vivoStoreConfig(), appVersion(bundle), new AppReleaseRecord(), "")
        );
        assertEquals("Vivo upload only supports APK packages", exception.getMessage());
    }

    @Test
    void shouldDownloadPackageFromRepositoryWhenLocalFileMissing() throws Exception {
        byte[] apkBytes = "repo-apk-binary".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Map<String, String>> requestParts = new AtomicReference<>();
        AtomicInteger repositoryHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> handleUpload(exchange, requestParts));
        server.createContext("/repository/packages/release.apk", exchange -> {
            repositoryHits.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, apkBytes.length);
            exchange.getResponseBody().write(apkBytes);
            exchange.close();
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(
                    new ObjectMapper(),
                    appProperties(server, "http://127.0.0.1:" + server.getAddress().getPort() + "/repository")
            );

            StoreSubmitResult result = publisher.submitRelease(
                    vivoStoreConfig(),
                    appVersion("packages/release.apk"),
                    new AppReleaseRecord(),
                    ""
            );

            assertEquals("serial-123", result.storeReleaseId());
            assertEquals(1, repositoryHits.get());
            assertEquals("release.apk", requestParts.get().get("file.filename"));
            assertEquals(md5Hex(apkBytes), requestParts.get().get("fileMd5"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldChooseVivoSandboxOrProductionBaseUrlByConfig() throws Exception {
        ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(new ObjectMapper(), new AppProperties());
        Method method = ConfigurableStorePublisher.class.getDeclaredMethod("vivoBaseUrl", StoreApiProperties.StoreEndpointProperties.class);
        method.setAccessible(true);

        StoreApiProperties.StoreEndpointProperties sandboxEndpoint = new StoreApiProperties.StoreEndpointProperties();
        sandboxEndpoint.setSandboxEnabled(true);
        assertEquals(
                "https://sandbox-developer-api.vivo.com.cn/router/rest",
                method.invoke(publisher, sandboxEndpoint)
        );

        StoreApiProperties.StoreEndpointProperties productionEndpoint = new StoreApiProperties.StoreEndpointProperties();
        productionEndpoint.setSandboxEnabled(false);
        assertEquals(
                "https://developer-api.vivo.com.cn/router/rest",
                method.invoke(publisher, productionEndpoint)
        );
    }

    private void handleUpload(HttpExchange exchange, AtomicReference<Map<String, String>> requestParts) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        assertTrue(contentType.contains("multipart/form-data"));
        requestParts.set(parseMultipart(contentType, exchange.getRequestBody().readAllBytes()));

        byte[] response = """
                {
                  "code": 0,
                  "subCode": "0",
                  "msg": "success",
                  "data": {
                    "serialnumber": "serial-123"
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void handleStageSubmit(HttpExchange exchange, AtomicReference<Map<String, String>> form) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        assertTrue(contentType.contains("application/x-www-form-urlencoded"));
        form.set(parseForm(exchange.getRequestBody().readAllBytes()));

        byte[] response = """
                {
                  "code": 0,
                  "subCode": "0",
                  "msg": "stage submit success"
                }
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void handleStageDetailQuery(HttpExchange exchange, AtomicReference<Map<String, String>> form) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        assertTrue(contentType.contains("application/x-www-form-urlencoded"));
        form.set(parseForm(exchange.getRequestBody().readAllBytes()));

        byte[] response = """
                {
                  "code": 0,
                  "subCode": "0",
                  "msg": "success",
                  "data": {
                    "packageName": "com.demo.app",
                    "auditStatus": 2,
                    "effectStatus": 1,
                    "stagedProportion": 66
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private AppProperties appProperties(HttpServer server) {
        AppProperties appProperties = new AppProperties();
        appProperties.setStorageRoot(tempDir.resolve("storage").toString());
        StoreApiProperties.StoreEndpointProperties vivo = new StoreApiProperties.StoreEndpointProperties();
        vivo.setMockEnabled(false);
        vivo.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/router/rest");
        appProperties.getStoreApi().getStores().put("vivo", vivo);
        return appProperties;
    }

    private AppProperties appProperties(HttpServer server, String packageRepositoryBaseUrl) {
        AppProperties appProperties = appProperties(server);
        appProperties.getPackageRepository().setBaseUrl(packageRepositoryBaseUrl);
        return appProperties;
    }

    private AppStoreConfig vivoStoreConfig() {
        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode("vivo"));
        storeConfig.setClientId("demo-access-key");
        storeConfig.setClientSecret("demo-access-secret");
        return storeConfig;
    }

    private AppVersion appVersion(Path packagePath) {
        AppInfo appInfo = new AppInfo();
        appInfo.setId(1L);
        appInfo.setAppName("DemoApp");
        appInfo.setPackageName("com.demo.app");
        appInfo.setAppDescription("Demo app description for staged release test");

        AppVersion version = new AppVersion();
        version.setAppId(1L);
        version.setAppInfo(appInfo);
        version.setVersionName("1.0.0");
        version.setVersionCode(100);
        version.setUpdateLog("Stage publish update log");
        version.setPackageUrl(packagePath.toString());
        return version;
    }

    private AppVersion appVersion(String packageUrl) {
        AppInfo appInfo = new AppInfo();
        appInfo.setId(1L);
        appInfo.setAppName("DemoApp");
        appInfo.setPackageName("com.demo.app");
        appInfo.setAppDescription("Demo app description for staged release test");

        AppVersion version = new AppVersion();
        version.setAppId(1L);
        version.setAppInfo(appInfo);
        version.setVersionName("1.0.0");
        version.setVersionCode(100);
        version.setUpdateLog("Stage publish update log");
        version.setPackageUrl(packageUrl);
        return version;
    }

    private AppReleaseRecord stagedRecord() {
        AppReleaseRecord record = new AppReleaseRecord();
        record.setReleaseType(2L);
        record.setGrayPercent(66L);
        record.setGrayStartTime(LocalDateTime.of(2026, 6, 1, 10, 0, 0));
        record.setGrayEndTime(LocalDateTime.of(2026, 6, 3, 10, 0, 0));
        return record;
    }

    private Map<String, String> parseMultipart(String contentType, byte[] body) {
        String boundary = extractBoundary(contentType);
        String content = new String(body, StandardCharsets.ISO_8859_1);
        String[] parts = content.split(Pattern.quote("--" + boundary));
        Map<String, String> result = new LinkedHashMap<>();
        Pattern dispositionPattern = Pattern.compile("name=\"([^\"]+)\"(?:; filename=\"([^\"]+)\")?");
        for (String part : parts) {
            if (part.isBlank() || "--\r\n".equals(part) || "--".equals(part)) {
                continue;
            }
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                continue;
            }
            String headers = part.substring(0, headerEnd);
            String value = part.substring(headerEnd + 4);
            value = value.replaceFirst("\r\n$", "");
            Matcher matcher = dispositionPattern.matcher(headers);
            if (!matcher.find()) {
                continue;
            }
            String name = matcher.group(1);
            String filename = matcher.group(2);
            if (filename != null) {
                result.put(name + ".filename", filename);
            } else {
                result.put(name, value);
            }
        }
        return result;
    }

    private Map<String, String> parseForm(byte[] body) {
        String content = new String(body, StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : content.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private String extractBoundary(String contentType) {
        for (String item : contentType.split(";")) {
            String trimmed = item.trim();
            if (trimmed.startsWith("boundary=")) {
                return trimmed.substring("boundary=".length()).replace("\"", "");
            }
        }
        throw new IllegalArgumentException("Multipart boundary is missing");
    }

    private String sign(Map<String, String> params, String accessSecret) throws Exception {
        String payload = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(accessSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private String md5Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update(data);
        return toHex(digest.digest());
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
}
