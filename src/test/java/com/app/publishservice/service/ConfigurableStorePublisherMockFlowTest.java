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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurableStorePublisherMockFlowTest {

    private static final List<String> STORE_TYPES = List.of(
            "huawei",
            "rongyao",
            "vivo",
            "oppo",
            "xiaomi",
            "sanxing",
            "yingyongbao"
    );

    @TempDir
    Path tempDir;

    /**
     * 测试各应用市场在 mock 模式下无需市场专属依赖也能走通 submit 和 poll。
     */
    @Test
    void shouldSupportFullLinkMockSubmitAndReviewForAllStores() throws Exception {
        ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(
                RestClient.create(),
                new ObjectMapper(),
                appProperties()
        );
        AppVersion version = appVersion();

        for (String storeTypeCode : STORE_TYPES) {
            AppStoreConfig storeConfig = storeConfig(storeTypeCode);
            AppReleaseRecord submitRecord = submitRecord(storeTypeCode);

            StoreSubmitResult submitResult = publisher.submitRelease(storeConfig, version, submitRecord, "");

            assertFalse(submitResult.message().isBlank());
            assertFalse(submitResult.storeReleaseId().isBlank());
            assertFalse(submitResult.requestLog().isBlank());
            assertFalse(submitResult.responseLog().isBlank());

            AppReleaseRecord reviewRecord = reviewRecord(storeTypeCode, version, submitResult.storeReleaseId());
            StoreReviewResult reviewResult = publisher.queryReview(storeConfig, reviewRecord, "");

            assertEquals(ReleaseStatus.PASS, reviewResult.releaseStatus(), storeTypeCode);
            assertFalse(reviewResult.responseLog().isBlank());
        }
    }

    @Test
    void shouldContinueSanxingSubmitWhenStagedRolloutRateReturns3208() throws Exception {
        Path apk = tempDir.resolve("sanxing-3208.apk");
        Files.writeString(apk, "sanxing-3208", StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final boolean[] submitCalled = {false};
        try {
            server.createContext("/seller/contentInfo", exchange -> {
                byte[] response = """
                        [
                          {
                            "contentId": "123456789012",
                            "contentStatus": "FOR_SALE",
                            "appTitle": "Sanxing Demo",
                            "defaultLanguageCode": "ENG",
                            "applicationType": "android",
                            "longDescription": "Existing description",
                            "newFeature": "",
                            "privatePolicyURL": "https://store.sanxing.test/privacy",
                            "paid": "N",
                            "publicationType": "01",
                            "binaryList": [
                              {
                                "binarySeq": "1",
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
                          "sessionId": "session-sanxing-3208",
                          "url": "%s"
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
                          "resultCode": "0000",
                          "fileKey": "file-key-sanxing-3208"
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.createContext("/seller/contentUpdate", exchange -> {
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
            server.createContext("/seller/v2/content/binary", exchange -> {
                byte[] response = """
                        {
                          "resultCode": "0000",
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
            server.createContext("/seller/v2/content/stagedRolloutBinary", exchange -> {
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
            server.createContext("/seller/v2/content/stagedRolloutRate", exchange -> {
                byte[] response;
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    response = """
                            {
                              "resultCode": "3208",
                              "resultMessage": "There is no binary set for staged rollout.",
                              "data": {
                                "rolloutRate": 0,
                                "countries": []
                              }
                            }
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, response.length);
                } else {
                    response = """
                            {
                              "resultCode": "3208",
                              "resultMessage": "There is no binary set for staged rollout."
                            }
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                }
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.createContext("/seller/contentSubmit", exchange -> {
                submitCalled[0] = true;
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();

            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(
                    RestClient.create(),
                    new ObjectMapper(),
                    sanxingHttpAppProperties(server)
            );
            AppStoreConfig storeConfig = storeConfig("sanxing");
            storeConfig.setAppId("123456789012");

            AppVersion version = appVersion();
            version.setPackageUrl32(apk.toString());
            version.setPackageUrl64(apk.toString());
            version.setPackageUrl(apk.toString());

            AppReleaseRecord record = submitRecord("sanxing");
            record.setReleaseType(2L);
            record.setGrayPercent(30L);

            StoreSubmitResult submitResult = publisher.submitRelease(storeConfig, version, record, "sanxing-token");

            assertTrue(submitCalled[0]);
            assertEquals("123456789012", submitResult.storeReleaseId());
            assertTrue(submitResult.responseLog().contains("\"resultCode\":\"3208\""));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 构建应用配置。
     */
    private AppProperties appProperties() {
        AppProperties appProperties = new AppProperties();
        appProperties.setStorageRoot(tempDir.resolve("storage").toString());
        appProperties.setReviewAutoPassSeconds(1L);
        for (String storeTypeCode : STORE_TYPES) {
            StoreApiProperties.StoreEndpointProperties endpoint = new StoreApiProperties.StoreEndpointProperties();
            endpoint.setMockEnabled(true);
            appProperties.getStoreApi().getStores().put(storeTypeCode, endpoint);
        }
        return appProperties;
    }

    private AppProperties sanxingHttpAppProperties(HttpServer server) {
        AppProperties appProperties = new AppProperties();
        appProperties.setStorageRoot(tempDir.resolve("storage").toString());
        StoreApiProperties.StoreEndpointProperties endpoint = new StoreApiProperties.StoreEndpointProperties();
        endpoint.setMockEnabled(false);
        endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        appProperties.getStoreApi().getStores().put("sanxing", endpoint);
        return appProperties;
    }

    /**
     * 构建应用版本。
     */
    private AppVersion appVersion() throws IOException {
        Path apk32 = tempDir.resolve("mock-32.apk");
        Path apk64 = tempDir.resolve("mock-64.apk");
        Files.writeString(apk32, "mock-32", StandardCharsets.UTF_8);
        Files.writeString(apk64, "mock-64", StandardCharsets.UTF_8);

        AppInfo appInfo = new AppInfo();
        appInfo.setId(1L);
        appInfo.setAppName("Mock Demo App");
        appInfo.setPackageName("com.demo.mock");
        appInfo.setAppDescription("Mock demo description");

        AppVersion version = new AppVersion();
        version.setId(10L);
        version.setAppId(1L);
        version.setAppInfo(appInfo);
        version.setVersionName("1.0.0");
        version.setVersionCode("100");
        version.setUpdateLog("Mock update log");
        version.setPackageUrl32(apk32.toString());
        version.setPackageUrl64(apk64.toString());
        version.setPackageUrl(apk64.toString());
        return version;
    }

    /**
     * 构建商店配置。
     */
    private AppStoreConfig storeConfig(String storeTypeCode) {
        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode(storeTypeCode));
        storeConfig.setClientId("mock-client-id");
        storeConfig.setClientSecret("mock-client-secret");
        storeConfig.setAccountName("mock-account");
        storeConfig.setEmail("mock@example.com");
        storeConfig.setPhone("13800138000");
        storeConfig.setPublicKey("mock-public-key");
        storeConfig.setPrivateKey("mock-private-key");
        return storeConfig;
    }

    /**
     * 构建提交记录。
     */
    private AppReleaseRecord submitRecord(String storeTypeCode) {
        AppReleaseRecord record = new AppReleaseRecord();
        record.setId(Math.abs((long) storeTypeCode.hashCode()));
        record.setStoreType(StoreType.fromCode(storeTypeCode));
        record.setReleaseType(1L);
        return record;
    }

    /**
     * 构建审核记录。
     */
    private AppReleaseRecord reviewRecord(String storeTypeCode, AppVersion version, String storeReleaseId) {
        AppReleaseRecord record = new AppReleaseRecord();
        record.setId(Math.abs((long) storeTypeCode.hashCode()) + 1000L);
        record.setStoreType(StoreType.fromCode(storeTypeCode));
        record.setStoreReleaseId(storeReleaseId);
        record.setPackageName(version.getAppInfo().getPackageName());
        record.setVersionCode(version.getVersionCode());
        record.setAppVersion(version);
        record.setReleaseTime(LocalDateTime.now().minusSeconds(2));
        return record;
    }
}
