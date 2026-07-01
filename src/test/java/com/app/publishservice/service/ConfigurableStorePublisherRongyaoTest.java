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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurableStorePublisherRongyaoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldRefreshRongyaoTokenByClientCredentials() throws Exception {
        AtomicReference<String> tokenRequestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/auth/token", exchange -> {
            tokenRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendJson(exchange, """
                    {
                      "access_token": "rongyao-token-123",
                      "expires_in": 3600,
                      "token_type": "Bearer"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            TokenPayload tokenPayload = publisher.refreshToken(rongyaoStoreConfig());

            assertEquals("access_token", tokenPayload.tokenType());
            assertEquals("rongyao-token-123", tokenPayload.tokenValue());
            assertTrue(tokenRequestBody.get().contains("grant_type=client_credentials"));
            assertTrue(tokenRequestBody.get().contains("client_id=demo-client-id"));
            assertTrue(tokenRequestBody.get().contains("client_secret=demo-client-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSubmitRongyaoFullReleaseWithPackageUploads() throws Exception {
        Path apk32 = tempDir.resolve("rongyao-32.apk");
        Path apk64 = tempDir.resolve("rongyao-64.apk");
        Files.writeString(apk32, "rongyao-32", StandardCharsets.UTF_8);
        Files.writeString(apk64, "rongyao-64", StandardCharsets.UTF_8);

        AtomicReference<Map<String, String>> appIdQuery = new AtomicReference<>();
        AtomicReference<Map<String, String>> appDetailQuery = new AtomicReference<>();
        AtomicReference<List<Map<String, Object>>> uploadUrlRequest = new AtomicReference<>();
        List<Map<String, String>> uploadQueries = new ArrayList<>();
        AtomicInteger uploadCounter = new AtomicInteger();
        AtomicReference<Map<String, Object>> updateFileInfoBody = new AtomicReference<>();
        AtomicReference<Map<String, Object>> updateLanguageInfoBody = new AtomicReference<>();
        AtomicReference<Map<String, Object>> submitAuditBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/openapi/v1/publish/get-app-id", exchange -> {
            appIdQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            sendJson(exchange, """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": [
                        {
                          "appId": 123456,
                          "packageName": "com.demo.rongyao"
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/openapi/v1/publish/get-app-detail", exchange -> {
            appDetailQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            sendJson(exchange, """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": {
                        "languageInfo": [
                          {
                            "languageId": "zh-CN",
                            "appName": "详情里的中文名",
                            "intro": "详情里的中文介绍",
                            "briefIntro": "详情里的中文短介绍",
                            "newFeature": "详情里的中文新特性"
                          },
                          {
                            "languageId": "en-US",
                            "appName": "Detail English Name",
                            "intro": "Detail English Intro",
                            "briefIntro": "Detail English Brief",
                            "newFeature": "Detail English Feature"
                          }
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/openapi/v1/publish/get-file-upload-url", exchange -> {
            uploadUrlRequest.set(jsonList(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            sendJson(exchange, """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": [
                        {
                          "fileName": "rongyao-64.apk",
                          "uploadUrl": "http://127.0.0.1:%d/upload/64",
                          "objectId": 9002
                        }
                      ]
                    }
                    """.formatted(server.getAddress().getPort()).getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/openapi/v1/publish/file-upload", exchange -> {
            uploadQueries.add(parseQuery(exchange.getRequestURI().getRawQuery()));
            exchange.getRequestBody().readAllBytes();
            uploadCounter.incrementAndGet();
            sendJson(exchange, """
                    {
                      "code": 0,
                      "msg": "success"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/openapi/v1/publish/update-file-info", exchange -> {
            updateFileInfoBody.set(jsonMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            sendJson(exchange, """
                    {
                      "code": 0,
                      "msg": "success"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/openapi/v1/publish/update-language-info", exchange -> {
            updateLanguageInfoBody.set(jsonMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            sendJson(exchange, """
                    {
                      "code": 0,
                      "msg": "success"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/openapi/v1/publish/submit-audit", exchange -> {
            submitAuditBody.set(jsonMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            sendJson(exchange, """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": "release-123"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            StoreSubmitResult result = publisher.submitRelease(rongyaoStoreConfig(), appVersion(apk32, apk64), releaseRecord(1L), "rongyao-access-token");

            assertEquals("com.demo.rongyao", appIdQuery.get().get("pkgName"));
            assertEquals("123456", appDetailQuery.get().get("appId"));
            assertEquals(1, uploadUrlRequest.get().size());
            assertEquals("rongyao-64.apk", uploadUrlRequest.get().get(0).get("fileName"));
            assertEquals(100, ((Number) uploadUrlRequest.get().get(0).get("fileType")).intValue());
            assertEquals("123456", uploadQueries.get(0).get("appId"));
            assertEquals("9002", uploadQueries.get(0).get("objectId"));
            assertEquals(1, uploadCounter.get());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> bindingFileList = (List<Map<String, Object>>) updateFileInfoBody.get().get("bindingFileList");
            assertEquals(1, bindingFileList.size());
            assertEquals(9002, ((Number) bindingFileList.get(0).get("objectId")).intValue());

            assertEquals(0, ((Number) updateLanguageInfoBody.get().get("setAll")).intValue());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> languageInfoList = (List<Map<String, Object>>) updateLanguageInfoBody.get().get("languageInfoList");
            assertEquals(2, languageInfoList.size());
            assertEquals("详情里的中文名", languageInfoList.get(0).get("appName"));
            assertEquals("详情里的中文介绍", languageInfoList.get(0).get("intro"));
            assertEquals("详情里的中文短介绍", languageInfoList.get(0).get("briefIntro"));
            assertEquals("Rongyao publish update description", languageInfoList.get(0).get("newFeature"));
            assertEquals("Rongyao publish update description", languageInfoList.get(1).get("newFeature"));

            assertEquals(1, ((Number) submitAuditBody.get().get("releaseType")).intValue());
            assertEquals("release-123", result.storeReleaseId());
            assertTrue(result.requestLog().contains("\"submitAudit\""));
            assertTrue(result.requestLog().contains("\"updateLanguageInfo\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSubmitRongyaoStagedRelease() throws Exception {
        Path apk32 = tempDir.resolve("stage-32.apk");
        Path apk64 = tempDir.resolve("stage-64.apk");
        Files.writeString(apk32, "stage-32", StandardCharsets.UTF_8);
        Files.writeString(apk64, "stage-64", StandardCharsets.UTF_8);

        AtomicReference<Map<String, Object>> updateLanguageInfoBody = new AtomicReference<>();
        AtomicReference<Map<String, Object>> submitAuditBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/openapi/v1/publish/get-app-id", exchange -> sendJson(exchange, """
                {
                  "code": 0,
                  "msg": "success",
                  "data": [
                    {
                      "appId": 123456,
                      "packageName": "com.demo.rongyao"
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/openapi/v1/publish/get-app-detail", exchange -> sendJson(exchange, """
                {
                  "code": 0,
                  "msg": "success",
                  "data": {
                    "languageInfo": [
                      {
                        "languageId": "zh-CN",
                        "appName": "阶段发布中文名",
                        "intro": "阶段发布中文介绍",
                        "briefIntro": "阶段发布中文短介绍",
                        "newFeature": "阶段发布旧特性"
                      }
                    ]
                  }
                }
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/openapi/v1/publish/get-file-upload-url", exchange -> sendJson(exchange, """
                {
                  "code": 0,
                  "msg": "success",
                  "data": [
                    {
                      "fileName": "stage-64.apk",
                      "uploadUrl": "http://127.0.0.1:%d/upload/64",
                      "objectId": 9102
                    }
                  ]
                }
                """.formatted(server.getAddress().getPort()).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/openapi/v1/publish/file-upload", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, """
                    {
                      "code": 0,
                      "msg": "success"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/openapi/v1/publish/update-file-info", exchange -> sendJson(exchange, """
                {
                  "code": 0,
                  "msg": "success"
                }
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/openapi/v1/publish/update-language-info", exchange -> {
            updateLanguageInfoBody.set(jsonMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            sendJson(exchange, """
                    {
                      "code": 0,
                      "msg": "success"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/openapi/v1/publish/submit-audit", exchange -> {
            submitAuditBody.set(jsonMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            sendJson(exchange, """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": "release-stage-123"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            AppReleaseRecord record = releaseRecord(2L);
            record.setGrayPercent(66L);
            record.setGrayStartTime(LocalDateTime.of(2026, 6, 18, 10, 0));
            record.setGrayEndTime(LocalDateTime.of(2026, 6, 20, 10, 0));

            StoreSubmitResult result = publisher.submitRelease(rongyaoStoreConfig(), appVersion(apk32, apk64), record, "rongyao-access-token");

            assertEquals(3, ((Number) submitAuditBody.get().get("releaseType")).intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> phasedReleaseInfo = (Map<String, Object>) submitAuditBody.get().get("phasedReleaseInfo");
            assertEquals("66.00", phasedReleaseInfo.get("releasePercentage"));
            assertEquals("2026-06-18T10:00:00+0800", phasedReleaseInfo.get("releaseStartDate"));
            assertEquals("2026-06-20T10:00:00+0800", phasedReleaseInfo.get("releaseEndDate"));
            assertNotNull(phasedReleaseInfo.get("releaseNote"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> languageInfoList = (List<Map<String, Object>>) updateLanguageInfoBody.get().get("languageInfoList");
            assertEquals("Rongyao publish update description", languageInfoList.get(0).get("newFeature"));
            assertEquals("release-stage-123", result.storeReleaseId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldQueryRongyaoReviewRejectResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/openapi/v1/publish/get-app-id", exchange -> sendJson(exchange, """
                {
                  "code": 0,
                  "msg": "success",
                  "data": [
                    {
                      "appId": 123456,
                      "packageName": "com.demo.rongyao"
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/openapi/v1/publish/get-audit-result", exchange -> sendJson(exchange, """
                {
                  "code": 0,
                  "msg": "success",
                  "data": [
                    {
                      "appId": 123456,
                      "releaseId": "release-123",
                      "auditResult": 2,
                      "auditMessage": "metadata invalid"
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8)));
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            AppReleaseRecord record = releaseRecord(1L);
            record.setPackageName("com.demo.rongyao");
            record.setStoreReleaseId("release-123");

            StoreReviewResult result = publisher.queryReview(rongyaoStoreConfig(), record, "rongyao-access-token");

            assertEquals(ReleaseStatus.REJECT, result.releaseStatus());
            assertEquals("metadata invalid", result.rejectReason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldQueryRongyaoStagedReviewFromPhasedReleaseInfo() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/openapi/v1/publish/get-app-id", exchange -> sendJson(exchange, """
                {
                  "code": 0,
                  "msg": "success",
                  "data": [
                    {
                      "appId": 123456,
                      "packageName": "com.demo.rongyao"
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/openapi/v1/publish/get-audit-result", exchange -> sendJson(exchange, """
                {
                  "code": 0,
                  "msg": "success",
                  "data": [
                    {
                      "appId": 123456,
                      "releaseId": "release-stage-123",
                      "auditResult": 3,
                      "auditMessage": ""
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/openapi/v1/publish/get-phased-release-info", exchange -> sendJson(exchange, """
                {
                  "releaseStatus": 2,
                  "releasePercentage": "30.00",
                  "releaseId": "release-stage-123"
                }
                """.getBytes(StandardCharsets.UTF_8)));
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            AppReleaseRecord record = releaseRecord(2L);
            record.setPackageName("com.demo.rongyao");
            record.setStoreReleaseId("release-stage-123");

            StoreReviewResult result = publisher.queryReview(rongyaoStoreConfig(), record, "rongyao-access-token");

            assertEquals(ReleaseStatus.PASS, result.releaseStatus());
            assertTrue(result.responseLog().contains("\"releaseStatus\":2"));
        } finally {
            server.stop(0);
        }
    }

    private AppProperties appProperties(HttpServer server) {
        AppProperties appProperties = new AppProperties();
        appProperties.setStorageRoot(tempDir.resolve("storage").toString());
        StoreApiProperties.StoreEndpointProperties rongyao = new StoreApiProperties.StoreEndpointProperties();
        rongyao.setMockEnabled(false);
        rongyao.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        rongyao.setTokenEndpoint("/auth/token");
        appProperties.getStoreApi().getStores().put("rongyao", rongyao);
        return appProperties;
    }

    private AppStoreConfig rongyaoStoreConfig() {
        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode("rongyao"));
        storeConfig.setClientId("demo-client-id");
        storeConfig.setClientSecret("demo-client-secret");
        return storeConfig;
    }

    private AppVersion appVersion(Path apk32, Path apk64) {
        AppInfo appInfo = new AppInfo();
        appInfo.setId(1L);
        appInfo.setAppName("Demo Rongyao App");
        appInfo.setPackageName("com.demo.rongyao");
        appInfo.setAppDescription("Rongyao publish update description");

        AppVersion version = new AppVersion();
        version.setId(1L);
        version.setAppId(1L);
        version.setAppInfo(appInfo);
        version.setVersionName("1.0.1");
        version.setVersionCode("101");
        version.setPackageUrl32(apk32.toString());
        version.setPackageUrl64(apk64.toString());
        version.setUpdateLog("Rongyao publish update");
        version.setCreateTime(LocalDateTime.now());
        return version;
    }

    private AppReleaseRecord releaseRecord(Long releaseType) {
        AppReleaseRecord record = new AppReleaseRecord();
        record.setId(1L);
        record.setStoreType(StoreType.fromCode("rongyao"));
        record.setReleaseType(releaseType);
        return record;
    }

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

    private Map<String, Object> jsonMap(String json) throws IOException {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private List<Map<String, Object>> jsonList(String json) throws IOException {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private void sendJson(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
