package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.common.exception.StoreApiException;
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
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    void shouldSubmitVivoFullReleaseWithUpdateRequest() throws Exception {
        Path apk = buildVivoPackage("demo-32.apk", "demo-64.apk");
        Map<String, Object> publishMetadata = buildVivoPublishMetadata(tempDir, tempDir.resolve("packages").resolve("demo-32.apk"), apk, false, 0);

        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
        AtomicReference<Map<String, String>> apk32RequestParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> apk64RequestParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> submitForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> {
            int index = requestCount.incrementAndGet();
            if (index == 1) {
                handleAppDetailQuery(exchange, queryForm);
                return;
            }
            if (index == 2) {
                handleUpload(exchange, apk32RequestParts, "serial-32");
                return;
            }
            if (index == 3) {
                handleUpload(exchange, apk64RequestParts, "serial-64");
                return;
            }
            handleAppSubmit(exchange, submitForm);
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server, publishMetadata));
            StoreSubmitResult result = publisher.submitRelease(vivoStoreConfig(), appVersion(apk), new AppReleaseRecord(), "");

            assertEquals("serial-64", result.storeReleaseId());
            assertEquals(4, requestCount.get());
            assertEquals("app.query.details", queryForm.get().get("method"));
            assertEquals("com.demo.app", queryForm.get().get("packageName"));
            Map<String, String> apk32Parts = apk32RequestParts.get();
            Map<String, String> apk64Parts = apk64RequestParts.get();
            assertEquals("app.upload.apk.app.32", apk32Parts.get("method"));
            assertEquals("app.upload.apk.app.64", apk64Parts.get("method"));
            assertEquals("demo-access-key", apk32Parts.get("access_key"));
            assertEquals("json", apk32Parts.get("format"));
            assertEquals("1.0", apk32Parts.get("v"));
            assertEquals("hmac", apk32Parts.get("sign_method"));
            assertEquals("developer", apk32Parts.get("target_app_key"));
            assertEquals("com.demo.app", apk32Parts.get("packageName"));
            assertEquals(md5Hex(Files.readAllBytes(tempDir.resolve("packages").resolve("demo-32.apk"))), apk32Parts.get("fileMd5"));
            assertEquals("demo-32.apk", apk32Parts.get("file.filename"));
            assertEquals("demo-64.apk", apk64Parts.get("file.filename"));

            Map<String, String> signSource = new LinkedHashMap<>();
            signSource.put("access_key", apk32Parts.get("access_key"));
            signSource.put("fileMd5", apk32Parts.get("fileMd5"));
            signSource.put("format", apk32Parts.get("format"));
            signSource.put("method", apk32Parts.get("method"));
            signSource.put("packageName", apk32Parts.get("packageName"));
            signSource.put("sign_method", apk32Parts.get("sign_method"));
            signSource.put("target_app_key", apk32Parts.get("target_app_key"));
            signSource.put("timestamp", apk32Parts.get("timestamp"));
            signSource.put("v", apk32Parts.get("v"));
            assertEquals(sign(signSource, "demo-access-secret"), apk32Parts.get("sign"));
            assertEquals("app.sync.update.subpackage.app", submitForm.get().get("method"));
            assertEquals("com.demo.app", submitForm.get().get("packageName"));
            assertEquals("100", submitForm.get().get("versionCode"));
            assertEquals("serial-32", submitForm.get().get("apk32"));
            assertEquals("serial-64", submitForm.get().get("apk64"));
            assertEquals("1", submitForm.get().get("onlineType"));
            assertEquals("1", submitForm.get().get("compatibleDevice"));
            assertTrue(result.requestLog().contains("appDetailsQuery"));
            assertTrue(result.requestLog().contains("appSubmit"));
            assertTrue(result.responseLog().contains("saleStatus"));
            assertTrue(result.responseLog().contains("submit success"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSubmitVivoReleaseWithRemotePackageStreamingWhenEnabled() throws Exception {
        byte[] apk32Bytes = "remote-demo-32".getBytes(StandardCharsets.UTF_8);
        byte[] apk64Bytes = "remote-demo-64".getBytes(StandardCharsets.UTF_8);

        AtomicInteger apk32DownloadCount = new AtomicInteger();
        AtomicInteger apk64DownloadCount = new AtomicInteger();
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
        AtomicReference<Map<String, String>> apk32RequestParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> apk64RequestParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> submitForm = new AtomicReference<>();
        HttpServer packageServer = HttpServer.create(new InetSocketAddress(0), 0);
        packageServer.createContext("/packages/demo-32.apk", exchange -> {
            apk32DownloadCount.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, apk32Bytes.length);
            try (var outputStream = exchange.getResponseBody()) {
                outputStream.write(apk32Bytes);
            }
        });
        packageServer.createContext("/packages/demo-64.apk", exchange -> {
            apk64DownloadCount.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, apk64Bytes.length);
            try (var outputStream = exchange.getResponseBody()) {
                outputStream.write(apk64Bytes);
            }
        });
        packageServer.start();

        HttpServer vivoServer = HttpServer.create(new InetSocketAddress(0), 0);
        vivoServer.createContext("/router/rest", exchange -> {
            int index = requestCount.incrementAndGet();
            if (index == 1) {
                handleAppDetailQuery(exchange, queryForm);
                return;
            }
            if (index == 2) {
                handleUpload(exchange, apk32RequestParts, "serial-32");
                return;
            }
            if (index == 3) {
                handleUpload(exchange, apk64RequestParts, "serial-64");
                return;
            }
            handleAppSubmit(exchange, submitForm);
        });
        vivoServer.start();

        try {
            int packagePort = packageServer.getAddress().getPort();
            AppProperties appProperties = appProperties(vivoServer);
            appProperties.getPackageRepository().setStreamUploadEnabled(true);
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties);
            AppVersion version = appVersion("http://127.0.0.1:" + packagePort + "/packages/demo-64.apk");
            version.setPackageUrl32("http://127.0.0.1:" + packagePort + "/packages/demo-32.apk");
            version.setPackageUrl64("http://127.0.0.1:" + packagePort + "/packages/demo-64.apk");

            StoreSubmitResult result = publisher.submitRelease(vivoStoreConfig(), version, new AppReleaseRecord(), "");

            assertEquals("serial-64", result.storeReleaseId());
            assertEquals(4, requestCount.get());
            assertEquals(2, apk32DownloadCount.get());
            assertEquals(2, apk64DownloadCount.get());
            assertEquals("app.upload.apk.app.32", apk32RequestParts.get().get("method"));
            assertEquals("app.upload.apk.app.64", apk64RequestParts.get().get("method"));
            assertEquals("demo-32.apk", apk32RequestParts.get().get("file.filename"));
            assertEquals("demo-64.apk", apk64RequestParts.get().get("file.filename"));
            assertEquals(md5Hex(apk32Bytes), apk32RequestParts.get().get("fileMd5"));
            assertEquals(md5Hex(apk64Bytes), apk64RequestParts.get().get("fileMd5"));
            assertTrue(result.requestLog().contains("\"fileSource\":\"remote\""));
        } finally {
            vivoServer.stop(0);
            packageServer.stop(0);
        }
    }

    @Test
    void shouldSubmitVivoStageReleaseWithCreateUpdateRequest() throws Exception {
        Path apk = buildVivoPackage("stage-demo-32.apk", "stage-demo-64.apk");
        Map<String, Object> publishMetadata = buildVivoPublishMetadata(tempDir, tempDir.resolve("packages").resolve("stage-demo-32.apk"), apk, false, 0);

        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
        AtomicReference<Map<String, String>> apk32UploadParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> apk64UploadParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> stageForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> {
            int index = requestCount.incrementAndGet();
            if (index == 1) {
                handleAppDetailQuery(exchange, queryForm);
                return;
            }
            if (index == 2) {
                handleUpload(exchange, apk32UploadParts, "serial-32");
                return;
            }
            if (index == 3) {
                handleUpload(exchange, apk64UploadParts, "serial-64");
                return;
            }
            handleStageSubmit(exchange, stageForm);
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server, publishMetadata));
            AppReleaseRecord record = stagedRecord();
            StoreSubmitResult result = publisher.submitRelease(vivoStoreConfig(), appVersion(apk), record, "");

            assertEquals("serial-64", result.storeReleaseId());
            assertEquals(4, requestCount.get());
            assertEquals("app.query.details", queryForm.get().get("method"));
            assertEquals("com.demo.app", queryForm.get().get("packageName"));
            assertEquals("app.upload.apk.app.32", apk32UploadParts.get().get("method"));
            assertEquals("app.upload.apk.app.64", apk64UploadParts.get().get("method"));
            assertEquals("1", apk32UploadParts.get().get("stageType"));
            assertEquals("1", apk64UploadParts.get().get("stageType"));
            assertEquals("app.sync.create.update.stage.app", stageForm.get().get("method"));
            assertEquals("com.demo.app", stageForm.get().get("packageName"));
            assertEquals("1", stageForm.get().get("subPackage"));
            assertEquals("2026-06-01 10:00:00", stageForm.get().get("stagedStartTime"));
            assertEquals("2026-06-03 10:00:00", stageForm.get().get("stagedEndTime"));
            assertEquals("66", stageForm.get().get("stagedProportion"));
            assertEquals("serial-32", stageForm.get().get("apkUuid32"));
            assertEquals("serial-64", stageForm.get().get("apkUuid64"));
            assertEquals("DemoApp", stageForm.get().get("mainTitle"));
            assertTrue(result.requestLog().contains("appDetailsQuery"));
            assertTrue(result.requestLog().contains("stageSubmit"));
            assertTrue(result.responseLog().contains("saleStatus"));
            assertTrue(result.responseLog().contains("stage submit success"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectVivoSubmitWhenAppDetailsNotApprovedAndOnline() throws Exception {
        byte[] apkBytes = "fake-apk-binary".getBytes(StandardCharsets.UTF_8);
        Path apk = tempDir.resolve("not-online.apk");
        Files.write(apk, apkBytes);

        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> {
            requestCount.incrementAndGet();
            handleAppDetailQuery(exchange, new AtomicReference<>(), 2, 0);
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server));

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> publisher.submitRelease(vivoStoreConfig(), appVersion(apk), new AppReleaseRecord(), "")
            );

            assertEquals(1, requestCount.get());
            assertTrue(exception.getMessage().contains("saleStatus=1 and status=3"));
            assertTrue(exception.getMessage().contains("current saleStatus=0"));
            assertTrue(exception.getMessage().contains("current status=2"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldCreateVivoAppWhenAppDetailsQueryReturns11001() throws Exception {
        Path apk = buildVivoCreatePackage();
        Map<String, Object> publishMetadata = buildVivoPublishMetadata(tempDir, tempDir.resolve("packages").resolve("create-demo-32.apk"), apk, true, 3);

        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
        AtomicReference<Map<String, String>> apk32UploadParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> apk64UploadParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> iconUploadParts = new AtomicReference<>();
        List<Map<String, String>> screenshotUploadParts = new ArrayList<>();
        AtomicReference<Map<String, String>> createForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> {
            int index = requestCount.incrementAndGet();
            if (index == 1) {
                handleAppDetailNotFoundQuery(exchange, queryForm);
                return;
            }
            if (index == 2) {
                handleUpload(exchange, apk32UploadParts, "serial-apk-32");
                return;
            }
            if (index == 3) {
                handleUpload(exchange, apk64UploadParts, "serial-apk-64");
                return;
            }
            if (index == 4) {
                handleUpload(exchange, iconUploadParts, "serial-icon");
                return;
            }
            if (index >= 5 && index <= 7) {
                AtomicReference<Map<String, String>> screenshotPart = new AtomicReference<>();
                handleUpload(exchange, screenshotPart, "serial-shot-" + (index - 4));
                screenshotUploadParts.add(screenshotPart.get());
                return;
            }
            handleCreateAppSubmit(exchange, createForm);
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server, publishMetadata));
            StoreSubmitResult result = publisher.submitRelease(vivoStoreConfig(), appVersion(apk), new AppReleaseRecord(), "");

            assertEquals("com.demo.app", result.storeReleaseId());
            assertEquals(8, requestCount.get());
            assertEquals("app.query.details", queryForm.get().get("method"));
            assertEquals("app.upload.apk.app.32", apk32UploadParts.get().get("method"));
            assertEquals("app.upload.apk.app.64", apk64UploadParts.get().get("method"));
            assertEquals("app.upload.icon", iconUploadParts.get().get("method"));
            assertEquals("icon.png", iconUploadParts.get().get("file.filename"));
            assertEquals(3, screenshotUploadParts.size());
            assertEquals("app.upload.screenshot", screenshotUploadParts.get(0).get("method"));
            assertEquals("screenshot-1.png", screenshotUploadParts.get(0).get("file.filename"));
            assertEquals("app.sync.create.subpackage.app", createForm.get().get("method"));
            assertEquals("serial-apk-32", createForm.get().get("apk32"));
            assertEquals("serial-apk-64", createForm.get().get("apk64"));
            assertEquals("serial-icon", createForm.get().get("icon"));
            assertEquals("serial-shot-1,serial-shot-2,serial-shot-3", createForm.get().get("screenshot"));
            assertEquals("5", createForm.get().get("appClassify"));
            assertEquals("501", createForm.get().get("subAppClassify"));
            assertEquals("12", createForm.get().get("rateAge"));
            assertEquals("1", createForm.get().get("compatibleDevice"));
            assertTrue(result.requestLog().contains("appCreate"));
            assertTrue(result.responseLog().contains("包名不正确"));
            assertTrue(result.responseLog().contains("create success"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldCreateVivoAppWithFiveScreenshots() throws Exception {
        Path apk = buildVivoCreatePackage();
        Map<String, Object> publishMetadata = buildVivoPublishMetadata(tempDir, tempDir.resolve("packages").resolve("create-demo-32.apk"), apk, true, 5);

        AtomicInteger requestCount = new AtomicInteger();
        List<Map<String, String>> screenshotUploadParts = new ArrayList<>();
        AtomicReference<Map<String, String>> createForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> {
            int index = requestCount.incrementAndGet();
            if (index == 1) {
                handleAppDetailNotFoundQuery(exchange, new AtomicReference<>());
                return;
            }
            if (index == 2) {
                handleUpload(exchange, new AtomicReference<>(), "serial-apk-32");
                return;
            }
            if (index == 3) {
                handleUpload(exchange, new AtomicReference<>(), "serial-apk-64");
                return;
            }
            if (index == 4) {
                handleUpload(exchange, new AtomicReference<>(), "serial-icon");
                return;
            }
            if (index >= 5 && index <= 9) {
                AtomicReference<Map<String, String>> screenshotPart = new AtomicReference<>();
                handleUpload(exchange, screenshotPart, "serial-shot-" + (index - 4));
                screenshotUploadParts.add(screenshotPart.get());
                return;
            }
            handleCreateAppSubmit(exchange, createForm);
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server, publishMetadata));
            StoreSubmitResult result = publisher.submitRelease(vivoStoreConfig(), appVersion(apk), new AppReleaseRecord(), "");

            assertEquals("com.demo.app", result.storeReleaseId());
            assertEquals(10, requestCount.get());
            assertEquals(5, screenshotUploadParts.size());
            assertEquals("screenshot-5.png", screenshotUploadParts.get(4).get("file.filename"));
            assertEquals(
                    "serial-shot-1,serial-shot-2,serial-shot-3,serial-shot-4,serial-shot-5",
                    createForm.get().get("screenshot")
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFailVivoCreateWhenAssetsAreMissing() throws Exception {
        Path apk = buildVivoCreatePackage();
        Map<String, Object> publishMetadata = buildVivoPublishMetadata(tempDir, tempDir.resolve("packages").resolve("create-demo-32.apk"), apk, false, 0);

        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> {
            requestCount.incrementAndGet();
            handleAppDetailNotFoundQuery(exchange, new AtomicReference<>());
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server, publishMetadata));

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> publisher.submitRelease(vivoStoreConfig(), appVersion(apk), new AppReleaseRecord(), "")
            );

            assertEquals(1, requestCount.get());
            assertTrue(exception.getMessage().contains("icon asset"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectVivoCreateWhenScreenshotCountIsLessThanThree() throws Exception {
        Path apk = buildVivoCreatePackage();
        Map<String, Object> publishMetadata = buildVivoPublishMetadata(tempDir, tempDir.resolve("packages").resolve("create-demo-32.apk"), apk, true, 2);

        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> {
            requestCount.incrementAndGet();
            handleAppDetailNotFoundQuery(exchange, new AtomicReference<>());
        });
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server, publishMetadata));

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> publisher.submitRelease(vivoStoreConfig(), appVersion(apk), new AppReleaseRecord(), "")
            );

            assertEquals(1, requestCount.get());
            assertTrue(exception.getMessage().contains("requires 3 to 5 screenshots"));
            assertTrue(exception.getMessage().contains("Current count=2"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectVivoCreateOutsideSandbox() throws Exception {
        Path apk = buildVivoCreatePackage();
        Map<String, Object> publishMetadata = buildVivoPublishMetadata(tempDir, tempDir.resolve("packages").resolve("create-demo-32.apk"), apk, true, 3);

        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> {
            requestCount.incrementAndGet();
            handleAppDetailNotFoundQuery(exchange, new AtomicReference<>());
        });
        server.start();

        try {
            AppProperties appProperties = appProperties(server, publishMetadata);
            appProperties.getStoreApi().getStore("vivo").setSandboxEnabled(false);
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties);

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> publisher.submitRelease(vivoStoreConfig(), appVersion(apk), new AppReleaseRecord(), "")
            );

            assertEquals(1, requestCount.get());
            assertTrue(exception.getMessage().contains("sandbox environment"));
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
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server));
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
    void shouldQueryVivoAppDetailsForFullRelease() throws Exception {
        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> handleAppDetailQuery(exchange, queryForm));
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server));
            AppReleaseRecord record = new AppReleaseRecord();
            record.setReleaseType(1L);
            record.setPackageName("com.demo.app");
            StoreReviewResult result = publisher.queryReview(vivoStoreConfig(), record, "");

            assertEquals(ReleaseStatus.PASS, result.releaseStatus());
            assertEquals("app.query.details", queryForm.get().get("method"));
            assertEquals("com.demo.app", queryForm.get().get("packageName"));
            assertTrue(result.responseLog().contains("saleStatus"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectNonApkPackageForVivo() throws Exception {
        Path bundle = tempDir.resolve("demo.aab");
        Files.writeString(bundle, "fake bundle", StandardCharsets.UTF_8);

        ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), new AppProperties());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> publisher.submitRelease(vivoStoreConfig(), appVersion(bundle), new AppReleaseRecord(), "")
        );
        assertEquals("Vivo upload only supports APK packages", exception.getMessage());
    }

    @Test
    void shouldDownloadPackageFromRepositoryWhenLocalFileMissing() throws Exception {
        byte[] apkBytes = "repo-apk-binary".getBytes(StandardCharsets.UTF_8);
        Path apk32 = tempDir.resolve("repo-32.apk");
        Files.writeString(apk32, "repo 32 bit apk", StandardCharsets.UTF_8);
        Map<String, Object> publishMetadata = Map.of(
                "vivo", Map.of("apk32Path", apk32.toAbsolutePath().toString().replace('\\', '/'))
        );
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<Map<String, String>> queryForm = new AtomicReference<>();
        AtomicReference<Map<String, String>> apk32UploadParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> apk64UploadParts = new AtomicReference<>();
        AtomicReference<Map<String, String>> submitForm = new AtomicReference<>();
        AtomicInteger repositoryHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> {
            int index = requestCount.incrementAndGet();
            if (index == 1) {
                handleAppDetailQuery(exchange, queryForm);
                return;
            }
            if (index == 2) {
                handleUpload(exchange, apk32UploadParts, "serial-32");
                return;
            }
            if (index == 3) {
                handleUpload(exchange, apk64UploadParts, "serial-64");
                return;
            }
            handleAppSubmit(exchange, submitForm);
        });
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
                    RestClient.create(),
                    new ObjectMapper(),
                    appProperties(server, "http://127.0.0.1:" + server.getAddress().getPort() + "/repository", publishMetadata)
            );

            StoreSubmitResult result = publisher.submitRelease(
                    vivoStoreConfig(),
                    appVersion("packages/release.apk"),
                    new AppReleaseRecord(),
                    ""
            );

            assertEquals("serial-64", result.storeReleaseId());
            assertEquals(4, requestCount.get());
            assertEquals(1, repositoryHits.get());
            assertEquals("app.query.details", queryForm.get().get("method"));
            assertEquals("repo-32.apk", apk32UploadParts.get().get("file.filename"));
            assertEquals("release.apk", apk64UploadParts.get().get("file.filename"));
            assertEquals(md5Hex(apkBytes), apk64UploadParts.get().get("fileMd5"));
            assertEquals("app.sync.update.subpackage.app", submitForm.get().get("method"));
            assertEquals("serial-32", submitForm.get().get("apk32"));
            assertEquals("serial-64", submitForm.get().get("apk64"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldChooseVivoSandboxOrProductionBaseUrlByConfig() throws Exception {
        VivoStorePlatformPublisher publisher = new VivoStorePlatformPublisher(RestClient.create(), new ObjectMapper(), new AppProperties(), null);

        StoreApiProperties.StoreEndpointProperties sandboxEndpoint = new StoreApiProperties.StoreEndpointProperties();
        sandboxEndpoint.setSandboxEnabled(true);
        assertEquals(
                "https://sandbox-developer-api.vivo.com.cn/router/rest",
                publisher.vivoBaseUrl(sandboxEndpoint)
        );

        StoreApiProperties.StoreEndpointProperties productionEndpoint = new StoreApiProperties.StoreEndpointProperties();
        productionEndpoint.setSandboxEnabled(false);
        assertEquals(
                "https://developer-api.vivo.com.cn/router/rest",
                publisher.vivoBaseUrl(productionEndpoint)
        );
    }

    @Test
    void shouldDecodeGbkMsgFromVivoErrorResponse() throws Exception {
        byte[] apkBytes = "fake-apk-binary".getBytes(StandardCharsets.UTF_8);
        Path apk = tempDir.resolve("gbk-error.apk");
        Files.write(apk, apkBytes);

        String expectedMessage = "\u6B64\u529F\u80FD\u4E0D\u5B58\u5728\uFF0C\u8BF7\u8054\u7CFBvivo\u5F00\u653E\u5E73\u53F0";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/router/rest", exchange -> handleGbkUploadError(exchange, expectedMessage));
        server.start();

        try {
            ConfigurableStorePublisher publisher = new ConfigurableStorePublisher(RestClient.create(), new ObjectMapper(), appProperties(server));

            StoreApiException exception = assertThrows(
                    StoreApiException.class,
                    () -> publisher.submitRelease(vivoStoreConfig(), appVersion(apk), new AppReleaseRecord(), "")
            );

            assertTrue(exception.getMessage().contains(expectedMessage));
            assertTrue(!exception.getMessage().contains("\u00E6"));
        } finally {
            server.stop(0);
        }
    }

    private void handleUpload(HttpExchange exchange, AtomicReference<Map<String, String>> requestParts) throws IOException {
        handleUpload(exchange, requestParts, "serial-123");
    }

    private void handleUpload(HttpExchange exchange, AtomicReference<Map<String, String>> requestParts, String serialNumber) throws IOException {
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
                    "serialnumber": "%s"
                  }
                }
                """.formatted(serialNumber).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void handleGbkUploadError(HttpExchange exchange, String message) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        assertTrue(requestBody.length > 0);

        byte[] response = (
                "{\"code\":10010,\"subCode\":\"-\",\"msg\":\"" + message + "\"}"
        ).getBytes(Charset.forName("GBK"));
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
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

    private void handleAppSubmit(HttpExchange exchange, AtomicReference<Map<String, String>> form) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        assertTrue(contentType.contains("application/x-www-form-urlencoded"));
        form.set(parseForm(exchange.getRequestBody().readAllBytes()));

        byte[] response = """
                {
                  "code": 0,
                  "subCode": "0",
                  "msg": "submit success"
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

    private void handleAppDetailQuery(HttpExchange exchange, AtomicReference<Map<String, String>> form) throws IOException {
        handleAppDetailQuery(exchange, form, 3, 1);
    }

    private void handleAppDetailQuery(HttpExchange exchange, AtomicReference<Map<String, String>> form, int status, int saleStatus) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        assertTrue(contentType.contains("application/x-www-form-urlencoded"));
        form.set(parseForm(exchange.getRequestBody().readAllBytes()));

        String responseBody = """
                {
                  "code": 0,
                  "subCode": "0",
                  "msg": "success",
                  "data": {
                    "packageName": "com.demo.app",
                    "status": %d,
                    "saleStatus": %d
                  }
                }
                """.formatted(status, saleStatus);
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void handleAppDetailNotFoundQuery(HttpExchange exchange, AtomicReference<Map<String, String>> form) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        assertTrue(contentType.contains("application/x-www-form-urlencoded"));
        form.set(parseForm(exchange.getRequestBody().readAllBytes()));

        byte[] response = """
                {
                  "code": 0,
                  "subCode": "11001",
                  "msg": "包名不正确，未查询到应用"
                }
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void handleCreateAppSubmit(HttpExchange exchange, AtomicReference<Map<String, String>> form) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        assertTrue(contentType.contains("application/x-www-form-urlencoded"));
        form.set(parseForm(exchange.getRequestBody().readAllBytes()));

        byte[] response = """
                {
                  "code": 0,
                  "subCode": "0",
                  "msg": "create success"
                }
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private AppProperties appProperties(HttpServer server) {
        return appProperties(server, Map.of());
    }

    private AppProperties appProperties(HttpServer server, Map<String, Object> publishMetadata) {
        AppProperties appProperties = new AppProperties();
        appProperties.setStorageRoot(tempDir.resolve("storage").toString());
        appProperties.getPublishMetadata().setBaseDir(tempDir.toString());
        appProperties.getPublishMetadata().setValues(publishMetadata);
        StoreApiProperties.StoreEndpointProperties vivo = new StoreApiProperties.StoreEndpointProperties();
        vivo.setMockEnabled(false);
        vivo.setSandboxEnabled(true);
        vivo.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/router/rest");
        appProperties.getStoreApi().getStores().put("vivo", vivo);
        return appProperties;
    }

    private AppProperties appProperties(HttpServer server, String packageRepositoryBaseUrl) {
        return appProperties(server, packageRepositoryBaseUrl, Map.of());
    }

    private AppProperties appProperties(HttpServer server, String packageRepositoryBaseUrl, Map<String, Object> publishMetadata) {
        AppProperties appProperties = appProperties(server, publishMetadata);
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
        version.setVersionCode("100");
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
        version.setVersionCode("100");
        version.setUpdateLog("Stage publish update log");
        version.setPackageUrl(packageUrl);
        return version;
    }

    private Path buildVivoCreatePackage() throws Exception {
        return buildVivoPackage("create-demo-32.apk", "create-demo-64.apk");
    }

    private Path buildVivoPackage(String apk32FileName, String apk64FileName) throws Exception {
        Path packageDir = tempDir.resolve("packages");
        Files.createDirectories(packageDir);
        Path apk32 = packageDir.resolve(apk32FileName);
        Path apk64 = packageDir.resolve(apk64FileName);
        Files.writeString(apk32, "fake 32 bit apk " + apk32FileName, StandardCharsets.UTF_8);
        Files.writeString(apk64, "fake 64 bit apk " + apk64FileName, StandardCharsets.UTF_8);
        return apk64;
    }

    private Map<String, Object> buildVivoPublishMetadata(Path projectRoot, Path apk32Path, Path apk64Path, boolean includeIcon, int screenshotCount) throws Exception {
        Path assetsDir = projectRoot.resolve("assets").resolve("vivo");
        Files.createDirectories(assetsDir);
        if (includeIcon) {
            Files.writeString(assetsDir.resolve("icon.png"), "icon-binary", StandardCharsets.UTF_8);
        }
        for (int index = 1; index <= screenshotCount; index++) {
            Files.writeString(assetsDir.resolve("screenshot-" + index + ".png"), "shot-" + index, StandardCharsets.UTF_8);
        }

        Map<String, Object> vivo = new LinkedHashMap<>();
        vivo.put("apk32Path", relativePath(projectRoot, apk32Path));
        if (apk64Path != null) {
            vivo.put("apk64Path", relativePath(projectRoot, apk64Path));
        }
        vivo.put("appClassify", 5);
        vivo.put("subAppClassify", 501);
        vivo.put("rateAge", 12);
        if (includeIcon) {
            vivo.put("iconPath", "assets/vivo/icon.png");
        }
        if (screenshotCount > 0) {
            vivo.put("screenshotPaths", buildScreenshotMetadataConfig(screenshotCount));
        }
        vivo.put("compatibleDevice", 1);
        return Map.of(
                "versionName", "1.0.0",
                "versionCode", 100,
                "reinforced", false,
                "vivo", vivo
        );
    }

    private String relativePath(Path projectRoot, Path filePath) {
        return projectRoot.toAbsolutePath().normalize().relativize(filePath.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private List<String> buildScreenshotMetadataConfig(int screenshotCount) {
        List<String> lines = new ArrayList<>();
        for (int index = 1; index <= screenshotCount; index++) {
            lines.add("assets/vivo/screenshot-" + index + ".png");
        }
        return lines;
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
