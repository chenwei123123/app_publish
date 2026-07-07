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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurableStorePublisherSanxingTest {

    private static final String CONTENT_ID = "123456789012";
    private static final String SERVICE_ACCOUNT_ID = "sa-sanxing-123";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    /**
     * 测试Refresh 令牌提交三星发布场景。
     */
    @Test
    void shouldRefreshTokenAndSubmitSanxingRelease() throws Exception {
        KeyPair keyPair = createKeyPair();
        Path apk = tempDir.resolve("demo-sanxing.apk");
        Files.writeString(apk, "sanxing-apk", StandardCharsets.UTF_8);

        AtomicReference<String> tokenAuthorization = new AtomicReference<>();
        AtomicReference<Map<String, String>> uploadForm = new AtomicReference<>();
        AtomicReference<Map<String, Object>> addBinaryPayload = new AtomicReference<>();
        AtomicReference<Map<String, Object>> contentUpdatePayload = new AtomicReference<>();
        AtomicReference<Map<String, Object>> stagedRolloutBinaryPayload = new AtomicReference<>();
        AtomicReference<Map<String, Object>> stagedRolloutPayload = new AtomicReference<>();
        AtomicReference<Map<String, String>> stagedRolloutQuery = new AtomicReference<>();
        AtomicReference<Map<String, Object>> disableStagedRolloutPayload = new AtomicReference<>();
        AtomicReference<Map<String, Object>> submitPayload = new AtomicReference<>();
        List<String> callOrder = new ArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/auth/accessToken", exchange -> {
            tokenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {
                      "ok": true,
                      "createdItem": {
                        "accessToken": "sanxing-token"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/contentInfo", exchange -> {
            byte[] response = """
                    [
                      {
                        "contentId": "123456789012",
                        "appTitle": "Sanxing Demo",
                        "contentStatus": "FOR_SALE",
                        "defaultLanguageCode": "ENG",
                        "applicationType": "android",
                        "longDescription": "Existing description",
                        "shortDescription": "",
                        "newFeature": "",
                        "ageLimit": "0",
                        "chinaAgeLimit": "0",
                        "openSourceURL": "",
                        "privatePolicyURL": "https://store.sanxing.test/privacy",
                        "youTubeURL": "",
                        "copyrightHolder": "",
                        "supportEMail": "support@demo.test",
                        "supportedSiteUrl": "",
                        "standardPrice": "0",
                        "paid": "N",
                        "publicationType": "01",
                        "reviewComment": "Store review comment",
                        "addLanguage": [{"languageCode": "ZHO"}],
                        "screenshots": [{"imageKey": "shot-key-1"}],
                        "sellCountryList": ["CN", "US"],
                        "usExportLaws": true,
                        "binaryList": [
                          {
                            "binarySeq": "1",
                            "versionCode": "100",
                            "versionName": "1.0.0",
                            "packageName": "com.demo.sanxing"
                          }
                        ],
                        "supportedLanguages": ["ENG"]
                      }
                    ]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/createUploadSessionId", exchange -> {
            String uploadUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/galaxyapi/fileUpload";
            byte[] response = ("""
                    {
                      "url": "%s",
                      "sessionId": "session-sanxing-1"
                    }
                    """.formatted(uploadUrl)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/galaxyapi/fileUpload", exchange -> {
            uploadForm.set(parseMultipart(exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {
                      "fileKey": "file-key-sanxing-1",
                      "fileName": "demo-sanxing.apk",
                      "fileSize": "11",
                      "errorCode": null,
                      "errorMsg": null
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/v2/content/binary", exchange -> {
            callOrder.add("addBinary");
            addBinaryPayload.set(jsonMapUnchecked(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = """
                    {
                      "resultCode": "0000",
                      "resultMessage": "Ok",
                      "data": {
                        "binarySeq": "2"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/contentUpdate", exchange -> {
            callOrder.add("contentUpdate");
            contentUpdatePayload.set(jsonMapUnchecked(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = """
                    {
                      "ctntId": "123456789012",
                      "contentStatus": "REGISTERING",
                      "httpStatus": "OK"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/v2/content/stagedRolloutRate", exchange -> {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                callOrder.add("queryStagedRollout");
                stagedRolloutQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
                byte[] response = """
                        {
                          "resultCode": "0000",
                          "resultMessage": "Ok",
                          "data": {
                            "rolloutRate": 20,
                            "countries": [
                              {
                                "countryCode": "USA",
                                "rolloutRate": 25
                              }
                            ]
                          }
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            Map<String, Object> payload = jsonMapUnchecked(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if ("DISABLE_ROLLOUT".equals(payload.get("function"))) {
                callOrder.add("disableStagedRollout");
                disableStagedRolloutPayload.set(payload);
            } else {
                callOrder.add("stagedRolloutRate");
                stagedRolloutPayload.set(payload);
            }
            byte[] response = """
                    {
                      "resultCode": "0000",
                      "resultMessage": "Ok",
                      "data": {
                        "rolloutRate": 30
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/contentSubmit", exchange -> {
            submitPayload.set(jsonMapUnchecked(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            AppStoreConfig storeConfig = sanxingStoreConfig(keyPair);
            TokenPayload tokenPayload = publisher.refreshToken(storeConfig);
            assertEquals("sanxing-token", tokenPayload.tokenValue());
            assertNotNull(tokenAuthorization.get());
            assertTrue(tokenAuthorization.get().startsWith("Bearer "));
            assertEquals(2, tokenAuthorization.get().chars().filter(ch -> ch == '.').count());

            StoreSubmitResult result = publisher.submitRelease(
                    storeConfig,
                    sanxingVersion(apk),
                    sanxingReleaseRecord(),
                    tokenPayload.tokenValue()
            );

            assertEquals("session-sanxing-1", uploadForm.get().get("sessionId"));
            assertEquals("demo-sanxing.apk", uploadForm.get().get("file.filename"));

            assertEquals(CONTENT_ID, addBinaryPayload.get().get("contentId"));
            assertEquals("file-key-sanxing-1", addBinaryPayload.get().get("filekey"));
            assertEquals("N", addBinaryPayload.get().get("gms"));
            assertEquals("1", addBinaryPayload.get().get("binarySeqForDeviceInfo"));

            assertEquals(CONTENT_ID, contentUpdatePayload.get().get("contentId"));
            assertEquals("Sanxing Demo", contentUpdatePayload.get().get("appTitle"));
            assertEquals("ENG", contentUpdatePayload.get().get("defaultLanguageCode"));
            assertEquals("N", contentUpdatePayload.get().get("paid"));
            assertEquals("01", contentUpdatePayload.get().get("publicationType"));
            assertEquals("Existing description", contentUpdatePayload.get().get("longDescription"));
            assertEquals("Sanxing description", contentUpdatePayload.get().get("newFeature"));
            assertEquals("Store review comment", contentUpdatePayload.get().get("reviewComment"));
            assertEquals("https://store.sanxing.test/privacy", contentUpdatePayload.get().get("privatePolicyURL"));
            assertEquals(List.of(Map.of("languageCode", "ZHO")), contentUpdatePayload.get().get("addLanguage"));
            assertEquals(List.of(Map.of("imageKey", "shot-key-1")), contentUpdatePayload.get().get("screenshots"));
            assertEquals(List.of("CN", "US"), contentUpdatePayload.get().get("sellCountryList"));
            assertEquals("123456789012", stagedRolloutQuery.get().get("contentId"));
            assertEquals("SALE", stagedRolloutQuery.get().get("appStatus"));
            assertEquals(CONTENT_ID, disableStagedRolloutPayload.get().get("contentId"));
            assertEquals("DISABLE_ROLLOUT", disableStagedRolloutPayload.get().get("function"));
            assertEquals("SALE", disableStagedRolloutPayload.get().get("appStatus"));
            assertEquals(List.of("queryStagedRollout", "disableStagedRollout", "contentUpdate", "addBinary"), callOrder.subList(0, 4));
            assertEquals(CONTENT_ID, stagedRolloutBinaryPayload.get().get("contentId"));
            assertEquals("ADD", stagedRolloutBinaryPayload.get().get("function"));
            assertEquals("2", stagedRolloutBinaryPayload.get().get("binarySeq"));
            assertEquals(CONTENT_ID, stagedRolloutPayload.get().get("contentId"));
            assertEquals("ENABLE_ROLLOUT", stagedRolloutPayload.get().get("function"));
            assertEquals("REGISTRATION", stagedRolloutPayload.get().get("appStatus"));
            assertEquals(30, ((Number) stagedRolloutPayload.get().get("rolloutRate")).intValue());
            assertEquals(List.of("queryStagedRollout", "disableStagedRollout", "contentUpdate", "addBinary", "stagedRolloutBinary", "stagedRolloutRate"), callOrder.subList(0, 6));
            assertTrue(result.requestLog().contains("\"stagedRolloutBinary\""));
            assertTrue(result.requestLog().contains("\"queryStagedRollout\""));
            assertTrue(result.requestLog().contains("\"disableStagedRollout\""));
            assertEquals(CONTENT_ID, submitPayload.get().get("contentId"));
            assertEquals(CONTENT_ID, result.storeReleaseId());
            assertEquals("submit success", result.message());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 测试查询三星 Rejected 审核状态场景。
     */
    @Test
    void shouldPreferSaleContentInfoWhenSubmittingSanxingUpdate() throws Exception {
        KeyPair keyPair = createKeyPair();
        Path apk = tempDir.resolve("demo-sanxing-sale.apk");
        Files.writeString(apk, "sanxing-apk-sale", StandardCharsets.UTF_8);

        AtomicReference<Map<String, Object>> addBinaryPayload = new AtomicReference<>();
        AtomicReference<Map<String, Object>> contentUpdatePayload = new AtomicReference<>();
        AtomicReference<Map<String, Object>> stagedRolloutBinaryPayload = new AtomicReference<>();
        AtomicReference<Map<String, String>> stagedRolloutQuery = new AtomicReference<>();
        List<String> callOrder = new ArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/auth/accessToken", exchange -> {
            byte[] response = """
                    {
                      "ok": true,
                      "createdItem": {
                        "accessToken": "sanxing-token"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/contentInfo", exchange -> {
            byte[] response = """
                    [
                      {
                        "contentId": "123456789012",
                        "appStatus": "REGISTERING",
                        "contentStatus": "REGISTERING",
                        "appTitle": "Draft Sanxing Demo",
                        "defaultLanguageCode": "KOR",
                        "applicationType": "android",
                        "longDescription": "Draft description",
                        "privatePolicyURL": "https://draft.sanxing.test/privacy",
                        "addLanguage": [{"languageCode": "KOR"}],
                        "screenshots": [{"imageKey": "draft-shot"}],
                        "sellCountryList": ["KR"],
                        "paid": "N",
                        "publicationType": "01",
                        "binaryList": [
                          {
                            "binarySeq": "99",
                            "versionCode": "100"
                          }
                        ]
                      },
                      {
                        "contentId": "123456789012",
                        "appStatus": "SALE",
                        "contentStatus": "FOR_SALE",
                        "appTitle": "Sale Sanxing Demo",
                        "defaultLanguageCode": "ENG",
                        "applicationType": "android",
                        "longDescription": "Sale description",
                        "privatePolicyURL": "https://sale.sanxing.test/privacy",
                        "addLanguage": [{"languageCode": "ENG"}],
                        "screenshots": [{"imageKey": "sale-shot"}],
                        "sellCountryList": ["US", "GB"],
                        "paid": "N",
                        "publicationType": "01",
                        "binaryList": [
                          {
                            "binarySeq": "7",
                            "versionCode": "100"
                          }
                        ]
                      }
                    ]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/createUploadSessionId", exchange -> {
            String uploadUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/galaxyapi/fileUpload";
            byte[] response = ("""
                    {
                      "url": "%s",
                      "sessionId": "session-sanxing-2"
                    }
                    """.formatted(uploadUrl)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/galaxyapi/fileUpload", exchange -> {
            byte[] response = """
                    {
                      "fileKey": "file-key-sanxing-2"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/v2/content/binary", exchange -> {
            callOrder.add("addBinary");
            addBinaryPayload.set(jsonMapUnchecked(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = """
                    {
                      "resultCode": "0000",
                      "data": {
                        "binarySeq": "8"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/v2/content/stagedRolloutBinary", exchange -> {
            callOrder.add("stagedRolloutBinary");
            stagedRolloutBinaryPayload.set(jsonMapUnchecked(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = """
                    {
                      "resultCode": "0000",
                      "resultMessage": "Ok"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/v2/content/stagedRolloutBinary", exchange -> {
            callOrder.add("stagedRolloutBinary");
            stagedRolloutBinaryPayload.set(jsonMapUnchecked(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = """
                    {
                      "resultCode": "0000",
                      "resultMessage": "Ok"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/contentUpdate", exchange -> {
            callOrder.add("contentUpdate");
            contentUpdatePayload.set(jsonMapUnchecked(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = """
                    {
                      "ctntId": "123456789012",
                      "contentStatus": "REGISTERING"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/v2/content/stagedRolloutRate", exchange -> {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                callOrder.add("queryStagedRollout");
                stagedRolloutQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
                byte[] response = """
                        {
                          "resultCode": "0000",
                          "resultMessage": "Ok",
                          "data": {
                            "rolloutRate": 0,
                            "countries": []
                          }
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            callOrder.add("stagedRolloutRate");
            byte[] response = """
                    {
                      "resultCode": "0000"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/seller/contentSubmit", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            AppStoreConfig storeConfig = sanxingStoreConfig(keyPair);
            TokenPayload tokenPayload = publisher.refreshToken(storeConfig);

            publisher.submitRelease(
                    storeConfig,
                    sanxingVersion(apk),
                    sanxingReleaseRecord(),
                    tokenPayload.tokenValue()
            );

            assertEquals("7", addBinaryPayload.get().get("binarySeqForDeviceInfo"));
            assertEquals("123456789012", stagedRolloutQuery.get().get("contentId"));
            assertEquals("SALE", stagedRolloutQuery.get().get("appStatus"));
            assertEquals("8", stagedRolloutBinaryPayload.get().get("binarySeq"));
            assertEquals("Sale Sanxing Demo", contentUpdatePayload.get().get("appTitle"));
            assertEquals("ENG", contentUpdatePayload.get().get("defaultLanguageCode"));
            assertEquals("Sale description", contentUpdatePayload.get().get("longDescription"));
            assertEquals("https://sale.sanxing.test/privacy", contentUpdatePayload.get().get("privatePolicyURL"));
            assertEquals(List.of(Map.of("languageCode", "ENG")), contentUpdatePayload.get().get("addLanguage"));
            assertEquals(List.of(Map.of("imageKey", "sale-shot")), contentUpdatePayload.get().get("screenshots"));
            assertEquals(List.of("US", "GB"), contentUpdatePayload.get().get("sellCountryList"));
            assertEquals(List.of("queryStagedRollout", "contentUpdate", "addBinary", "stagedRolloutBinary", "stagedRolloutRate"), callOrder.subList(0, 5));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldQuerySanxingRejectedReviewStatus() throws Exception {
        KeyPair keyPair = createKeyPair();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/seller/contentInfo", exchange -> {
            byte[] response = """
                    [
                      {
                        "contentId": "123456789012",
                        "contentStatus": "REJECTED",
                        "reviewComment": "Rejected by review",
                        "binaryList": [
                          {
                            "versionCode": "101"
                          }
                        ]
                      }
                    ]
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
                    sanxingStoreConfig(keyPair),
                    sanxingReviewRecord(),
                    "sanxing-token"
            );

            assertEquals(ReleaseStatus.REJECT, result.releaseStatus());
            assertEquals("Rejected by review", result.rejectReason());
            assertTrue(result.responseLog().contains("\"mappedStatus\":\"reject\""));
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
        appProperties.getPublishMetadata().setValues(Map.of(
                "sanxing", Map.of(
                        "gms", "N"
                )
        ));
        StoreApiProperties.StoreEndpointProperties sanxing = new StoreApiProperties.StoreEndpointProperties();
        sanxing.setMockEnabled(false);
        sanxing.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        appProperties.getStoreApi().getStores().put("sanxing", sanxing);
        return appProperties;
    }

    /**
     * 处理三星商店配置相关逻辑。
     */
    private AppStoreConfig sanxingStoreConfig(KeyPair keyPair) {
        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode("sanxing"));
        storeConfig.setClientId(SERVICE_ACCOUNT_ID);
        storeConfig.setPrivateKey(sanxingPrivateKeyJson(keyPair));
        storeConfig.setAppId(CONTENT_ID);
        return storeConfig;
    }

    /**
     * 处理三星版本相关逻辑。
     */
    private AppVersion sanxingVersion(Path apkPath) {
        AppInfo appInfo = new AppInfo();
        appInfo.setId(1L);
        appInfo.setAppName("Sanxing Demo");
        appInfo.setPackageName("com.demo.sanxing");
        appInfo.setAppDescription("Sanxing description");
        appInfo.setPrivacyUrl("https://example.com/privacy");

        AppVersion version = new AppVersion();
        version.setId(1L);
        version.setAppId(1L);
        version.setAppInfo(appInfo);
        version.setVersionName("1.0.1");
        version.setVersionCode("101");
        version.setUpdateLog("Sanxing review update");
        version.setPackageUrl64(apkPath.toString());
        version.setCreateTime(LocalDateTime.now());
        return version;
    }

    /**
     * 处理三星发布记录相关逻辑。
     */
    private AppReleaseRecord sanxingReleaseRecord() {
        AppReleaseRecord record = new AppReleaseRecord();
        record.setId(1L);
        record.setStoreType(StoreType.fromCode("sanxing"));
        record.setReleaseType(2L);
        record.setGrayPercent(30L);
        return record;
    }

    /**
     * 处理三星审核记录相关逻辑。
     */
    private AppReleaseRecord sanxingReviewRecord() {
        AppReleaseRecord record = new AppReleaseRecord();
        record.setId(2L);
        record.setStoreType(StoreType.fromCode("sanxing"));
        record.setVersionCode("101");
        return record;
    }

    /**
     * 创建Key Pair。
     */
    private KeyPair createKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /**
     * 处理三星 Private Key JSON相关逻辑。
     */
    private String sanxingPrivateKeyJson(KeyPair keyPair) {
        String pkcs8 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + wrap64(pkcs8) + "\n-----END PRIVATE KEY-----";
        return """
                {
                  "private_key": "%s"
                }
                """.formatted(pem.replace("\\", "\\\\").replace("\n", "\\n"));
    }

    /**
     * 处理wrap64相关逻辑。
     */
    private String wrap64(String value) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index += 64) {
            if (index > 0) {
                builder.append('\n');
            }
            builder.append(value, index, Math.min(index + 64, value.length()));
        }
        return builder.toString();
    }

    /**
     * 处理JSON 映射相关逻辑。
     */
    private Map<String, Object> jsonMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    /**
     * 处理JSON 映射 Unchecked相关逻辑。
     */
    private Map<String, Object> jsonMapUnchecked(String json) {
        try {
            return jsonMap(json);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse json: " + json, ex);
        }
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
}
