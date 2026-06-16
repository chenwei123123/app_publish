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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurableStorePublisherYingyongbaoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldSubmitYingyongbaoReleaseWithSignedUploadAndUpdateRequests() throws Exception {
        Path apk32 = tempDir.resolve("demo-32.apk");
        Path apk64 = tempDir.resolve("demo-64.apk");
        Files.writeString(apk32, "yyb-32", StandardCharsets.UTF_8);
        Files.writeString(apk64, "yyb-64", StandardCharsets.UTF_8);
        AtomicReference<Map<String, String>> appDetailForm = new AtomicReference<>();
        List<Map<String, String>> uploadInfoForms = new ArrayList<>();
        List<String> uploadedBodies = new ArrayList<>();
        AtomicReference<Map<String, String>> updateForm = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query_app_detail", exchange -> {
            appDetailForm.set(parseForm(exchange.getRequestBody().readAllBytes()));
            sendJson(exchange, """
                    {
                      "ret": 0,
                      "msg": "success",
                      "pkg_name": "com.demo.yyb.app",
                      "app_name": "Demo YYB App"
                    }
                    """);
        });
        server.createContext("/get_file_upload_info", exchange -> {
            Map<String, String> form = parseForm(exchange.getRequestBody().readAllBytes());
            uploadInfoForms.add(form);
            String fileName = form.get("file_name");
            sendJson(exchange, """
                    {
                      "ret": 0,
                      "msg": "success",
                      "pre_sign_url": "http://127.0.0.1:%d/upload/%s",
                      "serial_number": "serial-%s"
                    }
                    """.formatted(server.getAddress().getPort(), fileName, fileName));
        });
        server.createContext("/upload", exchange -> {
            uploadedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/update_app", exchange -> {
            updateForm.set(parseForm(exchange.getRequestBody().readAllBytes()));
            sendJson(exchange, """
                    {
                      "ret": 0,
                      "msg": "success"
                    }
                    """);
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            StoreSubmitResult result = publisher.submitRelease(yingyongbaoStoreConfig(), appVersion(apk32, apk64), new AppReleaseRecord(), "ignored-token");

            assertEquals("app-123", result.storeReleaseId());
            assertEquals("demo-user-id", appDetailForm.get().get("user_id"));
            assertEquals("app-123", appDetailForm.get().get("app_id"));
            assertEquals("com.demo.yyb.app", appDetailForm.get().get("pkg_name"));
            assertEquals(sign(without(appDetailForm.get(), "sign"), "demo-access-secret"), appDetailForm.get().get("sign"));

            assertEquals(2, uploadInfoForms.size());
            assertEquals("apk", uploadInfoForms.get(0).get("file_type"));
            assertEquals("demo-32.apk", uploadInfoForms.get(0).get("file_name"));
            assertEquals(sign(without(uploadInfoForms.get(0), "sign"), "demo-access-secret"), uploadInfoForms.get(0).get("sign"));
            assertEquals("demo-64.apk", uploadInfoForms.get(1).get("file_name"));
            assertEquals(2, uploadedBodies.size());
            assertEquals("yyb-32", uploadedBodies.get(0));
            assertEquals("yyb-64", uploadedBodies.get(1));

            assertEquals("app-123", updateForm.get().get("app_id"));
            assertEquals("com.demo.yyb.app", updateForm.get().get("pkg_name"));
            assertEquals("1", updateForm.get().get("deploy_type"));
            assertEquals("Yingyongbao publish update", updateForm.get().get("feature"));
            assertEquals("1", updateForm.get().get("apk32_flag"));
            assertEquals("1", updateForm.get().get("apk64_flag"));
            assertEquals("serial-demo-32.apk", updateForm.get().get("apk32_file_serial_number"));
            assertEquals("serial-demo-64.apk", updateForm.get().get("apk64_file_serial_number"));
            assertEquals(md5("yyb-32"), updateForm.get().get("apk32_file_md5"));
            assertEquals(md5("yyb-64"), updateForm.get().get("apk64_file_md5"));
            assertEquals(sign(without(updateForm.get(), "sign"), "demo-access-secret"), updateForm.get().get("sign"));
            assertTrue(result.responseLog().contains("serial-demo-32.apk"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldQueryYingyongbaoReviewStatus() throws Exception {
        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query_app_update_status", exchange -> {
            queryForm.set(parseForm(exchange.getRequestBody().readAllBytes()));
            sendJson(exchange, """
                    {
                      "ret": 0,
                      "msg": "success",
                      "audit_status": 2,
                      "audit_reason": "bad apk"
                    }
                    """);
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), objectMapper, appProperties(server));
            AppReleaseRecord record = new AppReleaseRecord();
            record.setId(19L);
            record.setStoreReleaseId("app-123");
            record.setPackageName("com.demo.yyb.app");

            StoreReviewResult result = publisher.queryReview(yingyongbaoStoreConfig(), record, "ignored-token");

            assertEquals("demo-user-id", queryForm.get().get("user_id"));
            assertEquals("app-123", queryForm.get().get("app_id"));
            assertEquals("com.demo.yyb.app", queryForm.get().get("pkg_name"));
            assertEquals(sign(without(queryForm.get(), "sign"), "demo-access-secret"), queryForm.get().get("sign"));
            assertEquals(ReleaseStatus.REJECT, result.releaseStatus());
            assertEquals("bad apk", result.rejectReason());
        } finally {
            server.stop(0);
        }
    }

    private AppProperties appProperties(HttpServer server) {
        AppProperties appProperties = new AppProperties();
        appProperties.setStorageRoot(tempDir.resolve("storage").toString());
        appProperties.getPublishMetadata().setBaseDir(tempDir.toString());
        appProperties.getPublishMetadata().setValues(Map.of(
                "yingyongbao", Map.of("appId", "app-123")
        ));
        StoreApiProperties.StoreEndpointProperties yingyongbao = new StoreApiProperties.StoreEndpointProperties();
        yingyongbao.setMockEnabled(false);
        yingyongbao.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        yingyongbao.setTokenEndpoint("/query_app_detail");
        yingyongbao.setSubmitEndpoint("/update_app");
        yingyongbao.setStatusEndpoint("/query_app_update_status");
        appProperties.getStoreApi().getStores().put("yingyongbao", yingyongbao);
        return appProperties;
    }

    private AppStoreConfig yingyongbaoStoreConfig() {
        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode("yingyongbao"));
        storeConfig.setClientId("demo-user-id");
        storeConfig.setClientSecret("demo-access-secret");
        return storeConfig;
    }

    private AppVersion appVersion(Path apk32, Path apk64) {
        AppInfo appInfo = new AppInfo();
        appInfo.setId(1L);
        appInfo.setAppName("Demo YYB App");
        appInfo.setPackageName("com.demo.yyb.app");
        appInfo.setAppDescription("Demo yingyongbao app description");

        AppVersion version = new AppVersion();
        version.setAppId(1L);
        version.setAppInfo(appInfo);
        version.setVersionName("1.0.1");
        version.setVersionCode("101");
        version.setUpdateLog("Yingyongbao publish update");
        version.setPackageUrl32(apk32.toString());
        version.setPackageUrl64(apk64.toString());
        version.setCreateTime(LocalDateTime.now());
        return version;
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
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

    private Map<String, String> without(Map<String, String> params, String keyToRemove) {
        Map<String, String> result = new LinkedHashMap<>(params);
        result.remove(keyToRemove);
        return result;
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

    private String md5(String content) throws Exception {
        return toHex(java.security.MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8)));
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
