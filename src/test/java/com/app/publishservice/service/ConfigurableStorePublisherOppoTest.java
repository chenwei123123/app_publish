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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurableStorePublisherOppoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldRefreshOppoTokenByClientCredentials() throws Exception {
        AtomicReference<Map<String, String>> queryParams = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/developer/v1/token", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            queryParams.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            byte[] response = """
                    {
                      "errno": 0,
                      "data": {
                        "access_token": "oppo-token-123",
                        "expire_in": 7200
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
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server));
            TokenPayload payload = publisher.refreshToken(oppoStoreConfig());

            assertEquals("access_token", payload.tokenType());
            assertEquals("oppo-token-123", payload.tokenValue());
            assertEquals("demo-client-id", queryParams.get().get("client_id"));
            assertEquals("demo-client-secret", queryParams.get().get("client_secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSubmitOppoReleaseWithSignedUploadAndUpdateRequests() throws Exception {
        Path apk32 = tempDir.resolve("demo-32.apk");
        Path apk64 = tempDir.resolve("demo-64.apk");
        Files.writeString(apk32, "fake-oppo-apk-32", StandardCharsets.UTF_8);
        Files.writeString(apk64, "fake-oppo-apk-64", StandardCharsets.UTF_8);

        AtomicReference<Map<String, String>> multiInfoQuery = new AtomicReference<>();
        List<Map<String, String>> uploadConfigQueries = new ArrayList<>();
        List<Map<String, String>> uploadParts = new ArrayList<>();
        AtomicReference<Map<String, String>> submitForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/resource/v1/app/multi-info", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            multiInfoQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            byte[] response = """
                    {
                      "errno": 0,
                      "data": {
                        "pkg_name": "com.demo.oppo.app",
                        "app_name": "Store Oppo App",
                        "second_category_id": "2001",
                        "third_category_id": "3001",
                        "copyright_url": "https://cdn.example.com/copyright.png",
                        "electronic_cert_url": "https://cdn.example.com/cert.pdf",
                        "icp_url": "ICP-2026-001",
                        "special_url": "https://cdn.example.com/special.png",
                        "business_username": "Store Contact",
                        "business_email": "store-contact@example.com",
                        "business_mobile": "13800138000",
                        "age_level": "3",
                        "adaptive_equipment": "4",
                        "adaptive_type": "1",
                        "customer_contact": [
                          {
                            "contact_method": "1",
                            "contact_info": "400-800-9000",
                            "working_hours": "09:00-18:00",
                            "weekend_hours": "10:00-17:00"
                          }
                        ],
                        "apk_info": {
                          "100": {
                            "app_name": "Store Oppo App",
                            "app_subname": "StoreSub",
                            "summary": "StoreSummary",
                            "detail_desc": "Store detail description",
                            "update_desc": "Store old update desc",
                            "privacy_source_url": "https://example.com/store-privacy",
                            "icon_url": "https://cdn.example.com/icon.png",
                            "pic_url": "https://cdn.example.com/shot1.png,https://cdn.example.com/shot2.png,https://cdn.example.com/shot3.png",
                            "landscape_pic_url": "https://cdn.example.com/land-1.png,https://cdn.example.com/land-2.png,https://cdn.example.com/land-3.png",
                            "online_type": "1",
                            "test_desc": "Store test description"
                          }
                        }
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/resource/v1/upload/get-upload-url", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            uploadConfigQueries.add(parseQuery(exchange.getRequestURI().getRawQuery()));
            byte[] response = """
                    {
                      "errno": 0,
                      "data": {
                        "upload_url": "http://127.0.0.1:%d/upload",
                        "sign": "single-use-sign",
                        "type": "apk"
                      }
                    }
                    """.formatted(server.getAddress().getPort()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/upload", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            assertTrue(contentType.contains("multipart/form-data"));
            uploadParts.add(parseMultipart(contentType, exchange.getRequestBody().readAllBytes()));
            String fileName = uploadParts.get(uploadParts.size() - 1).get("file.filename");
            byte[] response = """
                    {
                      "errno": 0,
                      "data": {
                        "apk_url": "https://cdn.example.com/%s",
                        "Md5": "upload-md5-%s"
                      }
                    }
                    """.formatted(fileName, fileName).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/resource/v1/app/upd", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            assertTrue(contentType.contains("application/x-www-form-urlencoded"));
            submitForm.set(parseForm(exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {
                      "errno": 0,
                      "data": {
                        "task_id": "task-123"
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
            StoreSubmitResult result = publisher.submitRelease(oppoStoreConfig(), appVersion(apk32, apk64), new AppReleaseRecord(), "oppo-access-token");
            List<Map<String, Object>> apkInfos = objectMapper.readValue(submitForm.get().get("apk_url"), new TypeReference<>() {});

            assertEquals("task-123", result.storeReleaseId());
            assertEquals("com.demo.oppo.app", multiInfoQuery.get().get("pkg_name"));
            assertEquals("oppo-access-token", multiInfoQuery.get().get("access_token"));
            assertEquals(sign(without(multiInfoQuery.get(), "api_sign"), "demo-client-secret"), multiInfoQuery.get().get("api_sign"));
            assertEquals(2, uploadConfigQueries.size());
            assertEquals("oppo-access-token", uploadConfigQueries.get(0).get("access_token"));
            assertEquals(sign(without(uploadConfigQueries.get(0), "api_sign"), "demo-client-secret"), uploadConfigQueries.get(0).get("api_sign"));
            assertEquals(sign(without(uploadConfigQueries.get(1), "api_sign"), "demo-client-secret"), uploadConfigQueries.get(1).get("api_sign"));
            assertEquals(2, uploadParts.size());
            assertEquals("single-use-sign", uploadParts.get(0).get("sign"));
            assertEquals("apk", uploadParts.get(0).get("type"));
            assertEquals("demo-32.apk", uploadParts.get(0).get("file.filename"));
            assertEquals("demo-64.apk", uploadParts.get(1).get("file.filename"));
            assertEquals("com.demo.oppo.app", submitForm.get().get("pkg_name"));
            assertEquals("101", submitForm.get().get("version_code"));
            assertEquals("Store Oppo App", submitForm.get().get("app_name"));
            assertEquals("StoreSub", submitForm.get().get("app_subname"));
            assertEquals("2001", submitForm.get().get("second_category_id"));
            assertEquals("3001", submitForm.get().get("third_category_id"));
            assertEquals("StoreSummary", submitForm.get().get("summary"));
            assertEquals("Store detail description", submitForm.get().get("detail_desc"));
            assertEquals("Store old update desc", submitForm.get().get("update_desc"));
            assertEquals("https://example.com/store-privacy", submitForm.get().get("privacy_source_url"));
            assertEquals("https://cdn.example.com/icon.png", submitForm.get().get("icon_url"));
            assertEquals("https://cdn.example.com/shot1.png,https://cdn.example.com/shot2.png,https://cdn.example.com/shot3.png", submitForm.get().get("pic_url"));
            assertEquals("https://cdn.example.com/land-1.png,https://cdn.example.com/land-2.png,https://cdn.example.com/land-3.png", submitForm.get().get("landscape_pic_url"));
            assertEquals("1", submitForm.get().get("online_type"));
            assertEquals("Store test description", submitForm.get().get("test_desc"));
            assertEquals("https://cdn.example.com/copyright.png", submitForm.get().get("copyright_url"));
            assertEquals("https://cdn.example.com/cert.pdf", submitForm.get().get("electronic_cert_url"));
            assertEquals("ICP-2026-001", submitForm.get().get("icp_url"));
            assertEquals("https://cdn.example.com/special.png", submitForm.get().get("special_url"));
            assertEquals("Store Contact", submitForm.get().get("business_username"));
            assertEquals("store-contact@example.com", submitForm.get().get("business_email"));
            assertEquals("13800138000", submitForm.get().get("business_mobile"));
            assertEquals("3", submitForm.get().get("age_level"));
            assertEquals("4", submitForm.get().get("adaptive_equipment"));
            assertEquals("1", submitForm.get().get("adaptive_type"));
            assertEquals("[{\"contact_method\":\"1\",\"contact_info\":\"400-800-9000\",\"working_hours\":\"09:00-18:00\",\"weekend_hours\":\"10:00-17:00\"}]", submitForm.get().get("customer_contact"));
            assertEquals(sign(without(submitForm.get(), "api_sign"), "demo-client-secret"), submitForm.get().get("api_sign"));
            assertEquals(2, apkInfos.size());
            assertEquals("https://cdn.example.com/demo-32.apk", apkInfos.get(0).get("url"));
            assertEquals("https://cdn.example.com/demo-64.apk", apkInfos.get(1).get("url"));
            assertEquals(32, ((Number) apkInfos.get(0).get("cpu_code")).intValue());
            assertEquals(64, ((Number) apkInfos.get(1).get("cpu_code")).intValue());
            assertEquals("upload-md5-demo-32.apk", apkInfos.get(0).get("md5"));
            assertEquals("upload-md5-demo-64.apk", apkInfos.get(1).get("md5"));
            assertTrue(result.responseLog().contains("task-123"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldQueryOppoReviewByTaskStateOnly() throws Exception {
        AtomicReference<Map<String, String>> taskStateForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/resource/v1/app/task-state", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            assertTrue(contentType.contains("application/x-www-form-urlencoded"));
            taskStateForm.set(parseForm(exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {
                      "errno": 0,
                      "data": {
                        "task_state": 2
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
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server));
            AppReleaseRecord record = new AppReleaseRecord();
            record.setId(11L);
            record.setPackageName("com.demo.oppo.app");
            record.setVersionCode("101");
            StoreReviewResult result = publisher.queryReview(oppoStoreConfig(), record, "oppo-access-token");

            assertEquals(ReleaseStatus.PASS, result.releaseStatus());
            assertEquals("com.demo.oppo.app", taskStateForm.get().get("pkg_name"));
            assertEquals("101", taskStateForm.get().get("version_code"));
            assertTrue(result.responseLog().contains("task_state"));
        } finally {
            server.stop(0);
        }
    }

    private AppProperties appProperties(HttpServer server) {
        AppProperties appProperties = new AppProperties();
        appProperties.setStorageRoot(tempDir.resolve("storage").toString());
        StoreApiProperties.StoreEndpointProperties oppo = new StoreApiProperties.StoreEndpointProperties();
        oppo.setMockEnabled(false);
        oppo.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        oppo.setTokenEndpoint("/developer/v1/token");
        oppo.setSubmitEndpoint("/resource/v1/app/upd");
        oppo.setStatusEndpoint("/resource/v1/app/task-state");
        appProperties.getStoreApi().getStores().put("oppo", oppo);
        return appProperties;
    }

    private AppStoreConfig oppoStoreConfig() {
        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode("oppo"));
        storeConfig.setClientId("demo-client-id");
        storeConfig.setClientSecret("demo-client-secret");
        return storeConfig;
    }

    private AppVersion appVersion(Path packagePath32, Path packagePath64) {
        AppInfo appInfo = new AppInfo();
        appInfo.setId(1L);
        appInfo.setAppName("Demo Oppo App");
        appInfo.setPackageName("com.demo.oppo.app");
        appInfo.setAppDescription("Demo oppo app description");

        AppVersion version = new AppVersion();
        version.setAppId(1L);
        version.setAppInfo(appInfo);
        version.setVersionName("1.0.1");
        version.setVersionCode("101");
        version.setUpdateLog("Oppo publish update");
        version.setPackageUrl32(packagePath32.toString());
        version.setPackageUrl64(packagePath64.toString());
        version.setCreateTime(LocalDateTime.now());
        return version;
    }

    private Map<String, String> parseQuery(String rawQuery) {
        return parsePairs(rawQuery == null ? new byte[0] : rawQuery.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> parseForm(byte[] body) {
        return parsePairs(body);
    }

    private Map<String, String> parsePairs(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
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

    private String extractBoundary(String contentType) {
        for (String item : contentType.split(";")) {
            String trimmed = item.trim();
            if (trimmed.startsWith("boundary=")) {
                return trimmed.substring("boundary=".length()).replace("\"", "");
            }
        }
        throw new IllegalArgumentException("Multipart boundary is missing");
    }

    private Map<String, String> without(Map<String, String> params, String keyToRemove) {
        Map<String, String> result = new LinkedHashMap<>(params);
        result.remove(keyToRemove);
        return result;
    }

    private String sign(Map<String, String> params, String clientSecret) throws Exception {
        String payload = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
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

    private String md5(String content) throws Exception {
        return toHex(java.security.MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
