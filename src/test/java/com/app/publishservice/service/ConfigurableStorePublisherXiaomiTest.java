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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import javax.crypto.Cipher;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAKey;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurableStorePublisherXiaomiTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    /**
     * 测试提交小米发布查询多部分表单 Push场景。
     */
    @Test
    void shouldSubmitXiaomiReleaseWithQueryAndMultipartPush() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        Path packagesDir = Files.createDirectories(tempDir.resolve("packages"));
        Path assetsDir = Files.createDirectories(tempDir.resolve("assets").resolve("xiaomi"));
        Path apk64 = packagesDir.resolve("demo.apk");
        Path fallbackApk = packagesDir.resolve("fallback.apk");
        Path icon = assetsDir.resolve("icon.png");
        Path screenshot1 = assetsDir.resolve("screenshot-1.png");
        Path screenshot2 = assetsDir.resolve("screenshot-2.png");
        Path screenshot3 = assetsDir.resolve("screenshot-3.png");
        Files.writeString(apk64, "xiaomi-apk", StandardCharsets.UTF_8);
        Files.writeString(icon, "xiaomi-icon", StandardCharsets.UTF_8);
        Files.writeString(screenshot1, "xiaomi-shot-1", StandardCharsets.UTF_8);
        Files.writeString(screenshot2, "xiaomi-shot-2", StandardCharsets.UTF_8);
        Files.writeString(screenshot3, "xiaomi-shot-3", StandardCharsets.UTF_8);

        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
        AtomicReference<Map<String, String>> submitForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/dev/query", exchange -> {
            queryForm.set(parseMultipart(exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {
                      "result": 0,
                      "message": "query success",
                      "create": true,
                      "updateVersion": false,
                      "updateInfo": false
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/dev/push", exchange -> {
            submitForm.set(parseMultipart(exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {
                      "result": 0,
                      "message": "submit success"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            StoreSubmitResult result = publisher.submitRelease(
                    xiaomiStoreConfig(keyPair),
                    appVersion(apk64, null, fallbackApk),
                    new AppReleaseRecord(),
                    ""
            );

            Map<String, Object> queryRequestData = jsonMap(queryForm.get().get("RequestData"));
            assertEquals("com.demo.xiaomi.app", queryRequestData.get("packageName"));
            assertEquals("demo@xiaomi.test", queryRequestData.get("userName"));

            Map<String, Object> querySig = jsonMap(decryptSig(queryForm.get().get("SIG"), keyPair.getPrivate()));
            assertEquals("mi-access-password", querySig.get("password"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> querySigEntries = (List<Map<String, Object>>) querySig.get("sig");
            assertEquals("RequestData", querySigEntries.get(0).get("name"));
            assertEquals(md5Hex(queryForm.get().get("RequestData").getBytes(StandardCharsets.UTF_8)), querySigEntries.get(0).get("hash"));

            Map<String, Object> submitRequestData = jsonMap(submitForm.get().get("RequestData"));
            assertEquals("demo@xiaomi.test", submitRequestData.get("userName"));
            assertEquals(0, ((Number) submitRequestData.get("synchroType")).intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> appInfo = (Map<String, Object>) submitRequestData.get("appInfo");
            assertEquals("Demo Xiaomi App", appInfo.get("appName"));
            assertEquals("com.demo.xiaomi.app", appInfo.get("packageName"));
            assertEquals("https://store-config.example.com/privacy", appInfo.get("privacyUrl"));
            assertEquals("demo.apk", submitForm.get().get("apk.filename"));
            assertNull(submitForm.get().get("secondApk.filename"));
            assertTrue(submitForm.get().get("icon.filename").startsWith("xiaomi-icon-"));
            assertEquals("screenshot-1.png", submitForm.get().get("screenshot_1.filename"));
            assertEquals("screenshot-2.png", submitForm.get().get("screenshot_2.filename"));
            assertEquals("screenshot-3.png", submitForm.get().get("screenshot_3.filename"));

            Map<String, Object> submitSig = jsonMap(decryptSig(submitForm.get().get("SIG"), keyPair.getPrivate()));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> submitSigEntries = (List<Map<String, Object>>) submitSig.get("sig");
            List<String> partNames = submitSigEntries.stream().map(entry -> String.valueOf(entry.get("name"))).toList();
            assertEquals(List.of("RequestData", "apk", "icon", "screenshot_1", "screenshot_2", "screenshot_3"), partNames);
            assertTrue(result.requestLog().contains("\"packageQuery\""));
            assertTrue(result.responseLog().contains("submit success"));
            assertEquals("com.demo.xiaomi.app:101", result.storeReleaseId());
            assertEquals("submit success", result.message());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 测试查询小米审核 Infer Pass When Target 版本 Is Live场景。
     */
    @Test
    void shouldQueryXiaomiReviewAndInferPassWhenTargetVersionIsLive() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/dev/query", exchange -> {
            queryForm.set(parseMultipart(exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {
                      "result": 0,
                      "message": "query success",
                      "packageInfo": {
                        "packageName": "com.demo.xiaomi.app",
                        "versionCode": "101",
                        "versionName": "1.0.1"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            StoreReviewResult result = publisher.queryReview(
                    xiaomiStoreConfig(keyPair),
                    xiaomiReviewRecord("101", LocalDateTime.now().minusHours(2)),
                    ""
            );

            Map<String, Object> queryRequestData = jsonMap(queryForm.get().get("RequestData"));
            assertEquals("com.demo.xiaomi.app", queryRequestData.get("packageName"));
            assertEquals("demo@xiaomi.test", queryRequestData.get("userName"));
            assertEquals(ReleaseStatus.PASS, result.releaseStatus());
            assertTrue(result.responseLog().contains("\"matchedTargetVersion\":true"));
            assertTrue(result.responseLog().contains("\"inferredStatus\":\"pass\""));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 测试Keep Auditing When 小米查询 Cannot Confirm Target 版本场景。
     */
    @Test
    void shouldKeepAuditingWhenXiaomiQueryCannotConfirmTargetVersion() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/dev/query", exchange -> {
            byte[] response = """
                    {
                      "result": 0,
                      "message": "query success",
                      "packageInfo": {
                        "packageName": "com.demo.xiaomi.app",
                        "versionCode": "100",
                        "versionName": "1.0.0"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            StoreReviewResult result = publisher.queryReview(
                    xiaomiStoreConfig(keyPair),
                    xiaomiReviewRecord("101", LocalDateTime.now().minusDays(1)),
                    ""
            );

            assertEquals(ReleaseStatus.AUDITING, result.releaseStatus());
            assertTrue(result.responseLog().contains("\"matchedTargetVersion\":false"));
            assertTrue(result.responseLog().contains("\"inferredStatus\":\"auditing\""));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 处理应用 Properties相关逻辑。
     */
    private AppProperties appProperties(HttpServer server) {
        AppProperties appProperties = new AppProperties();
        appProperties.setStorageRoot(tempDir.resolve("storage").toString());
        appProperties.getPublishMetadata().setBaseDir(tempDir.toString());
        appProperties.getPublishMetadata().setValues(xiaomiPublishMetadata());
        StoreApiProperties.StoreEndpointProperties xiaomi = new StoreApiProperties.StoreEndpointProperties();
        xiaomi.setMockEnabled(false);
        xiaomi.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        xiaomi.setTokenEndpoint("/dev/query");
        xiaomi.setSubmitEndpoint("/dev/push");
        appProperties.getStoreApi().getStores().put("xiaomi", xiaomi);
        return appProperties;
    }

    /**
     * 处理小米发布元数据相关逻辑。
     */
    private Map<String, Object> xiaomiPublishMetadata() {
        return Map.of(
                "xiaomi", Map.of(
                        "category", 10,
                        "keyWords", "demo office",
                        "desc", "Demo xiaomi description",
                        "brief", "Demo brief",
                        "publisherName", "Demo Publisher",
                        "screenshotPaths", List.of(
                                "assets/xiaomi/screenshot-1.png",
                                "assets/xiaomi/screenshot-2.png",
                                "assets/xiaomi/screenshot-3.png"
                        ),
                        "testAccount", Map.of(
                                "zh_CN", Map.of(
                                        "accounts", List.of(Map.of(
                                                "t", 1,
                                                "a", "demo",
                                                "p", "secret"
                                        )),
                                        "auditNotes", "notes"
                                )
                        ),
                        "onlineTime", 1780000000000L,
                        "suitableType", 0
                )
        );
    }

    /**
     * 处理小米商店配置相关逻辑。
     */
    private AppStoreConfig xiaomiStoreConfig(KeyPair keyPair) {
        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode("xiaomi"));
        storeConfig.setEmail("demo@xiaomi.test");
        storeConfig.setPrivateKey("mi-access-password");
        storeConfig.setPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        storeConfig.setPrivacyUrl("https://store-config.example.com/privacy");
        storeConfig.setIcon(Base64.getEncoder().encodeToString("store-config-icon".getBytes(StandardCharsets.UTF_8)));
        return storeConfig;
    }

    /**
     * 处理应用版本相关逻辑。
     */
    private AppVersion appVersion(Path package64Path, Path secondApk, Path fallbackPackagePath) {
        AppInfo appInfo = new AppInfo();
        appInfo.setId(1L);
        appInfo.setAppName("Demo Xiaomi App");
        appInfo.setPackageName("com.demo.xiaomi.app");
        appInfo.setAppDescription("Demo xiaomi description");

        AppVersion version = new AppVersion();
        version.setId(1L);
        version.setAppId(1L);
        version.setAppInfo(appInfo);
        version.setVersionName("1.0.1");
        version.setVersionCode("101");
        version.setUpdateLog("Xiaomi release update");
        version.setPackageUrl(fallbackPackagePath.toString());
        version.setPackageUrl64(package64Path.toString());
        if (secondApk != null) {
            version.setPackageUrl32(secondApk.toString());
        }
        version.setCreateTime(LocalDateTime.now());
        return version;
    }

    /**
     * 处理小米审核记录相关逻辑。
     */
    private AppReleaseRecord xiaomiReviewRecord(String versionCode, LocalDateTime releaseTime) {
        AppReleaseRecord record = new AppReleaseRecord();
        record.setId(1L);
        record.setStoreType(StoreType.fromCode("xiaomi"));
        record.setPackageName("com.demo.xiaomi.app");
        record.setVersionCode(versionCode);
        record.setStoreReleaseId("com.demo.xiaomi.app:" + versionCode);
        record.setReleaseTime(releaseTime);

        AppVersion appVersion = new AppVersion();
        appVersion.setVersionCode(versionCode);
        appVersion.setVersionName("1.0.1");
        record.setAppVersion(appVersion);
        return record;
    }

    /**
     * 处理JSON 映射相关逻辑。
     */
    private Map<String, Object> jsonMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    /**
     * 解析多部分表单。
     */
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
            String value = part.substring(headerEnd + 4).replaceFirst("\r\n$", "");
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

    /**
     * 提取Boundary。
     */
    private String extractBoundary(String contentType) {
        for (String item : contentType.split(";")) {
            String trimmed = item.trim();
            if (trimmed.startsWith("boundary=")) {
                return trimmed.substring("boundary=".length()).replace("\"", "");
            }
        }
        throw new IllegalArgumentException("Multipart boundary is missing");
    }

    /**
     * 解密Sig。
     */
    private String decryptSig(String hex, PrivateKey privateKey) throws Exception {
        byte[] encrypted = hexToBytes(hex);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        int blockSize = (((RSAKey) privateKey).getModulus().bitLength() + 7) / 8;
        List<byte[]> chunks = new ArrayList<>();
        int totalSize = 0;
        for (int offset = 0; offset < encrypted.length; offset += blockSize) {
            byte[] chunk = cipher.doFinal(encrypted, offset, Math.min(blockSize, encrypted.length - offset));
            chunks.add(chunk);
            totalSize += chunk.length;
        }
        byte[] plain = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, plain, offset, chunk.length);
            offset += chunk.length;
        }
        return new String(plain, StandardCharsets.UTF_8);
    }

    /**
     * 处理hex Bytes相关逻辑。
     */
    private byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            int position = index * 2;
            bytes[index] = (byte) Integer.parseInt(hex.substring(position, position + 2), 16);
        }
        return bytes;
    }

    /**
     * 计算 MD5 摘要Hex。
     */
    private String md5Hex(byte[] bytes) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
        digest.update(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            String hex = Integer.toHexString(value & 0xFF);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }


}
