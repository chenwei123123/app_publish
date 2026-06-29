package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.domain.entity.AppInfo;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.app.publishservice.service.model.TokenPayload;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurableStorePublisherHuaweiTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    /**
     * 测试Refresh 华为令牌客户端 Credentials场景。
     */
    @Test
    void shouldRefreshHuaweiTokenByClientCredentials() throws Exception {
        AtomicReference<Map<String, Object>> tokenRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/oauth2/v1/token", exchange -> {
            tokenRequest.set(jsonMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = """
                    {
                      "access_token": "huawei-token-123",
                      "expires_in": 172800
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
            TokenPayload tokenPayload = publisher.refreshToken(huaweiStoreConfig());

            assertEquals("access_token", tokenPayload.tokenType());
            assertEquals("huawei-token-123", tokenPayload.tokenValue());
            assertEquals("client_credentials", tokenRequest.get().get("grant_type"));
            assertEquals("demo-client-id", tokenRequest.get().get("client_id"));
            assertEquals("demo-client-secret", tokenRequest.get().get("client_secret"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 测试提交华为发布 Split 包上传场景。
     */
    @Test
    void shouldSubmitHuaweiReleaseWithSplitPackageUpload() throws Exception {
        Path apk32 = tempDir.resolve("demo-32.apk");
        Path apk64 = tempDir.resolve("demo-64.apk");
        Files.writeString(apk32, "huawei-32", StandardCharsets.UTF_8);
        Files.writeString(apk64, "huawei-64", StandardCharsets.UTF_8);

        AtomicReference<Map<String, String>> appIdQuery = new AtomicReference<>();
        List<Map<String, String>> uploadUrlQueries = new ArrayList<>();
        List<String> uploadedBodies = new ArrayList<>();
        AtomicReference<Map<String, Object>> fileInfoBody = new AtomicReference<>();
        AtomicReference<Map<String, String>> fileInfoQuery = new AtomicReference<>();
        AtomicReference<Map<String, Object>> submitBody = new AtomicReference<>();
        AtomicReference<Map<String, String>> submitQuery = new AtomicReference<>();
        AtomicInteger uploadCounter = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/publish/v2/appid-list", exchange -> {
            appIdQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            byte[] response = """
                    {
                      "ret": {
                        "code": 0,
                        "msg": "success"
                      },
                      "appids": [
                        {
                          "key": "Demo Huawei App",
                          "value": "app-123"
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            sendJson(exchange, response);
        });
        server.createContext("/api/publish/v2/upload-url/for-obs", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            uploadUrlQueries.add(query);
            String fileName = query.get("fileName");
            String objectId = fileName + ".object";
            byte[] response = """
                    {
                      "ret": {
                        "code": 0,
                        "msg": "success"
                      },
                      "urlInfo": {
                        "objectId": "%s",
                        "url": "http://127.0.0.1:%d/upload/%s",
                        "method": "PUT",
                        "headers": {
                          "Content-Type": "application/octet-stream",
                          "x-amz-date": "20260608T120000Z"
                        }
                      }
                    }
                    """.formatted(objectId, server.getAddress().getPort(), objectId).getBytes(StandardCharsets.UTF_8);
            sendJson(exchange, response);
        });
        server.createContext("/upload", exchange -> {
            uploadedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            uploadCounter.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/publish/v2/app-file-info", exchange -> {
            fileInfoQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            fileInfoBody.set(jsonMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = """
                    {
                      "ret": {
                        "code": 0,
                        "msg": "success"
                      },
                      "pkgVersion": [
                        "pkg-version-1"
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            sendJson(exchange, response);
        });
        server.createContext("/api/publish/v2/app-submit", exchange -> {
            submitQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            submitBody.set(jsonMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = """
                    {
                      "ret": {
                        "code": 0,
                        "msg": "success"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            sendJson(exchange, response);
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            StoreSubmitResult result = publisher.submitRelease(huaweiStoreConfig(), appVersion(apk32, apk64), new AppReleaseRecord(), "huawei-access-token");

            assertEquals("com.demo.huawei.app", appIdQuery.get().get("packageName"));
            assertEquals(1, uploadUrlQueries.size());
            assertEquals("app-123", uploadUrlQueries.get(0).get("appId"));
            assertEquals("demo-64.apk", uploadUrlQueries.get(0).get("fileName"));
            assertEquals("1", uploadUrlQueries.get(0).get("releaseType"));
            assertEquals(1, uploadCounter.get());
            assertEquals("huawei-64", uploadedBodies.get(0));
            assertEquals("app-123", fileInfoQuery.get().get("appId"));
            assertEquals("1", fileInfoQuery.get().get("releaseType"));
            assertEquals(5, ((Number) fileInfoBody.get().get("fileType")).intValue());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) fileInfoBody.get().get("files");
            assertEquals(1, files.size());
            assertEquals("demo-64.apk", files.get(0).get("fileName"));
            assertEquals("demo-64.apk.object", files.get(0).get("fileDestUrl"));
            assertEquals("app-123", submitQuery.get().get("appId"));
            assertEquals("1", submitQuery.get().get("releaseType"));
            assertTrue(submitQuery.get().get("remark").length() >= 10);
            assertTrue(submitBody.get().isEmpty());
            assertEquals("app-123", result.storeReleaseId());
            assertTrue(result.requestLog().contains("\"uploadPackages\""));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 测试提交华为灰度发布场景。
     */
    @Test
    void shouldSubmitHuaweiStagedRelease() throws Exception {
        Path apk32 = tempDir.resolve("stage-32.apk");
        Path apk64 = tempDir.resolve("stage-64.apk");
        Files.writeString(apk32, "stage-32", StandardCharsets.UTF_8);
        Files.writeString(apk64, "stage-64", StandardCharsets.UTF_8);

        AtomicReference<Map<String, String>> fileInfoQuery = new AtomicReference<>();
        AtomicReference<Map<String, String>> submitQuery = new AtomicReference<>();
        AtomicReference<Map<String, Object>> submitBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/publish/v2/appid-list", exchange -> sendJson(exchange, """
                {
                  "ret": { "code": 0, "msg": "success" },
                  "appids": [
                    { "key": "Demo Huawei App", "value": "app-456" }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/api/publish/v2/upload-url/for-obs", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String fileName = query.get("fileName");
            String objectId = fileName + ".object";
            sendJson(exchange, """
                    {
                      "ret": { "code": 0, "msg": "success" },
                      "urlInfo": {
                        "objectId": "%s",
                        "url": "http://127.0.0.1:%d/upload/%s",
                        "method": "PUT",
                        "headers": {
                          "Content-Type": "application/octet-stream"
                        }
                      }
                    }
                    """.formatted(objectId, server.getAddress().getPort(), objectId).getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/upload", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/publish/v2/app-file-info", exchange -> {
            fileInfoQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            sendJson(exchange, """
                    {
                      "ret": { "code": 0, "msg": "success" },
                      "pkgVersion": ["pkg-version-2"]
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/api/publish/v2/app-submit", exchange -> {
            submitQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            submitBody.set(jsonMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            sendJson(exchange, """
                    {
                      "ret": { "code": 0, "msg": "success" }
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            AppReleaseRecord record = new AppReleaseRecord();
            record.setReleaseType(2L);
            record.setGrayPercent(66L);
            record.setGrayStartTime(LocalDateTime.of(2026, 6, 8, 10, 0));
            record.setGrayEndTime(LocalDateTime.of(2026, 6, 10, 10, 0));

            StoreSubmitResult result = publisher.submitRelease(huaweiStoreConfig(), appVersion(apk32, apk64), record, "huawei-access-token");

            assertEquals("3", fileInfoQuery.get().get("releaseType"));
            assertEquals("3", submitQuery.get().get("releaseType"));
            assertEquals("2026-06-08T10:00:00+0800", submitBody.get().get("phasedReleaseStartTime"));
            assertEquals("2026-06-10T10:00:00+0800", submitBody.get().get("phasedReleaseEndTime"));
            assertEquals("66.00", submitBody.get().get("phasedReleasePercent"));
            assertTrue(String.valueOf(submitBody.get().get("phasedReleaseDescription")).length() >= 10);
            assertEquals("app-456", result.storeReleaseId());
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
        StoreApiProperties.StoreEndpointProperties huawei = new StoreApiProperties.StoreEndpointProperties();
        huawei.setMockEnabled(false);
        huawei.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        huawei.setTokenEndpoint("/oauth2/v1/token");
        huawei.setSubmitEndpoint("/publish/v2/app-submit");
        huawei.setStatusEndpoint("/publish/v2/app-info");
        appProperties.getStoreApi().getStores().put("huawei", huawei);
        return appProperties;
    }

    /**
     * 处理华为商店配置相关逻辑。
     */
    private AppStoreConfig huaweiStoreConfig() {
        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode("huawei"));
        storeConfig.setClientId("demo-client-id");
        storeConfig.setClientSecret("demo-client-secret");
        return storeConfig;
    }

    /**
     * 处理应用版本相关逻辑。
     */
    private AppVersion appVersion(Path apk32, Path apk64) {
        AppInfo appInfo = new AppInfo();
        appInfo.setId(1L);
        appInfo.setAppName("Demo Huawei App");
        appInfo.setPackageName("com.demo.huawei.app");
        appInfo.setAppDescription("Huawei publish update description");

        AppVersion version = new AppVersion();
        version.setId(1L);
        version.setAppId(1L);
        version.setAppInfo(appInfo);
        version.setVersionName("1.0.1");
        version.setVersionCode("101");
        version.setPackageUrl32(apk32.toString());
        version.setPackageUrl64(apk64.toString());
        version.setUpdateLog("Huawei publish update");
        version.setCreateTime(LocalDateTime.now());
        return version;
    }

    /**
     * 解析查询。
     */
    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
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

    /**
     * 处理JSON 映射相关逻辑。
     */
    private Map<String, Object> jsonMap(String json) throws IOException {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    /**
     * 发送JSON。
     */
    private void sendJson(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
