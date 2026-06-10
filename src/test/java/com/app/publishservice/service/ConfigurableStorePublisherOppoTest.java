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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurableStorePublisherOppoTest {

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
        Path apk = tempDir.resolve("demo.apk");
        Files.writeString(apk, "fake-oppo-apk", StandardCharsets.UTF_8);

        AtomicReference<Map<String, String>> uploadConfigQuery = new AtomicReference<>();
        AtomicReference<Map<String, String>> uploadParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> submitForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/resource/v1/upload/get-upload-url", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            uploadConfigQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
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
            uploadParts.set(parseMultipart(contentType, exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {
                      "errno": 0,
                      "data": {
                        "apk_url": "https://cdn.example.com/demo.apk"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
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
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server));
            StoreSubmitResult result = publisher.submitRelease(oppoStoreConfig(), appVersion(apk), new AppReleaseRecord(), "oppo-access-token");

            assertEquals("task-123", result.storeReleaseId());
            assertEquals("oppo-access-token", uploadConfigQuery.get().get("access_token"));
            assertEquals(sign(without(uploadConfigQuery.get(), "api_sign"), "demo-client-secret"), uploadConfigQuery.get().get("api_sign"));
            assertEquals("single-use-sign", uploadParts.get().get("sign"));
            assertEquals("apk", uploadParts.get().get("type"));
            assertEquals("demo.apk", uploadParts.get().get("file.filename"));
            assertEquals("com.demo.oppo.app", submitForm.get().get("pkg_name"));
            assertEquals("101", submitForm.get().get("version_code"));
            assertEquals("https://cdn.example.com/demo.apk", submitForm.get().get("apk_url"));
            assertEquals(sign(without(submitForm.get(), "api_sign"), "demo-client-secret"), submitForm.get().get("api_sign"));
            assertTrue(result.responseLog().contains("task-123"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldQueryOppoReviewByAppInfoAndTaskState() throws Exception {
        AtomicReference<Map<String, String>> infoQuery = new AtomicReference<>();
        AtomicReference<Map<String, String>> taskStateForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/resource/v1/app/info", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            infoQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            byte[] response = """
                    {
                      "errno": 0,
                      "data": {
                        "audit_status": 2,
                        "audit_status_name": "\u5ba1\u6838\u901a\u8fc7"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
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
            assertEquals("com.demo.oppo.app", infoQuery.get().get("pkg_name"));
            assertEquals("101", infoQuery.get().get("version_code"));
            assertEquals("com.demo.oppo.app", taskStateForm.get().get("pkg_name"));
            assertEquals("101", taskStateForm.get().get("version_code"));
            assertTrue(result.responseLog().contains("audit_status_name"));
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

    private AppVersion appVersion(Path packagePath) {
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
        version.setPackageUrl(packagePath.toString());
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
}
