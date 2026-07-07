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
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
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

    private static final String TEST_XIAOMI_CERTIFICATE_BASE64 =
            "MIIDczCCAlugAwIBAgIINwvzQIQihIowDQYJKoZIhvcNAQEMBQAwaDELMAkGA1UEBhMCQ04xETAPBgNVBAgTCFNoYW5naGFpMREwDwYDVQQHEwhTaGFuZ2hhaTEQMA4GA1UEChMHRXhhbXBsZTELMAkGA1UECxMCUUExFDASBgNVBAMTC1Rlc3QgWGlhb21pMB4XDTI2MDcwNjAyNTM0N1oXDTM2MDcwMzAyNTM0N1owaDELMAkGA1UEBhMCQ04xETAPBgNVBAgTCFNoYW5naGFpMREwDwYDVQQHEwhTaGFuZ2hhaTEQMA4GA1UEChMHRXhhbXBsZTELMAkGA1UECxMCUUExFDASBgNVBAMTC1Rlc3QgWGlhb21pMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArW2HEMxHlAaYE5s/zLuhrOw7i4QKPBheHGjdvfFHOX940sbQOywPFYNjukcDmw968TReh+zlLMMorQ8Zr0NWlDTZ96Z1BLg2qvEs4QZpOpdY16WtpT4Lhe6IISVy+J1zcTO71Zq6VrlablRa+3ungmc49tzFyzP9xfb+QrOOq6viUkU1JMmD+rdUzV5BS8kckBbII7d5+yNLxAVSTD62POAlWt8ykCvfSMJYmRUsv5HDwkjXPtecLbTtWLuJmBgvKN3grWmMaynBGdpyND3dYeItA9fLT4Y6Kj6pLCvnIamsewPJ8qY05QtDmouMlK1mS+ll11q57SyvLOPHkWcUDwIDAQABoyEwHzAdBgNVHQ4EFgQUiIa0+L1Dop/l0RaEY0/+oMgT9AowDQYJKoZIhvcNAQEMBQADggEBABHIjEYO7ipV6AKU16WxGgfEKOKtycaDeB7QXssKaQhwqESGXwt12UU7nD74BkYt20BKd3sxi7oeM3x4MbfpSVIc4Ho6Vm6rNrWLu6SokV0Hp+yzTHP/dw8NHDxH55sy76zwUrx3Hmhfm9mGwBp5OWj4SKGaj3pcdsDmg947Ibe2ZD0sTb8I9xEMH6OSHaiTkPYWdY64kFiBRHcOkuf+GLiCZd3sxCfixUExw+0SU9siNWFsQu1FuAPfv6YbNR2aevnS4py70XCTgJchzj/+95ZjiRWwg45O2tku8wR9ehx1puNEQyoz11OslBDH3vzEdfGEnHLKCFuI8UHjVA1AT+w=";

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

            assertEquals(queryForm.get().get("RequestData"), decryptSig(queryForm.get().get("SIG"), keyPair.getPublic()));

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

            assertEquals(submitForm.get().get("RequestData"), decryptSig(submitForm.get().get("SIG"), keyPair.getPublic()));
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

    @Test
    void shouldSubmitXiaomiReleaseWhenPublicKeyIsCertificateBase64() throws Exception {
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

        PublicKey certificatePublicKey = xiaomiCertificatePublicKey();
        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
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
            AppStoreConfig storeConfig = xiaomiStoreConfig(null);
            storeConfig.setPublicKey(TEST_XIAOMI_CERTIFICATE_BASE64);
            StoreSubmitResult result = publisher.submitRelease(
                    storeConfig,
                    appVersion(apk64, null, fallbackApk),
                    new AppReleaseRecord(),
                    ""
            );

            assertEquals(queryForm.get().get("RequestData"), decryptSig(queryForm.get().get("SIG"), certificatePublicKey));
            assertEquals("submit success", result.message());
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
        if (keyPair != null) {
            storeConfig.setPrivateKey(xiaomiPrivateKeyPem(keyPair));
            storeConfig.setPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        } else {
            storeConfig.setPrivateKey("xiaomi-access-password");
            storeConfig.setPublicKey(TEST_XIAOMI_CERTIFICATE_BASE64);
        }
        storeConfig.setPrivacyUrl("https://store-config.example.com/privacy");
        storeConfig.setIcon(Base64.getEncoder().encodeToString("store-config-icon".getBytes(StandardCharsets.UTF_8)));
        return storeConfig;
    }

    private String xiaomiPrivateKeyPem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
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

    private PublicKey xiaomiCertificatePublicKey() throws GeneralSecurityException {
        byte[] certificateBytes = Base64.getDecoder().decode(TEST_XIAOMI_CERTIFICATE_BASE64);
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) certificateFactory.generateCertificate(
                new ByteArrayInputStream(certificateBytes)
        );
        return certificate.getPublicKey();
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
    private String decryptSig(String hex, PublicKey publicKey) throws Exception {
        byte[] encrypted = hexToBytes(hex);
        Cipher cipher = xiaomiCipher();
        cipher.init(Cipher.DECRYPT_MODE, publicKey);
        int blockSize = (((RSAKey) publicKey).getModulus().bitLength() + 7) / 8;
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

    private Cipher xiaomiCipher() throws Exception {
        try {
            return Cipher.getInstance("RSA/NONE/PKCS1Padding");
        } catch (Exception ex) {
            return Cipher.getInstance("RSA/ECB/PKCS1Padding");
        }
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
}
