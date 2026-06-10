package com.app.publishservice.util;

import com.app.publishservice.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApkDownloadUtilTest {

    @AfterEach
    void tearDown() {
        ApkDownloadUtil.configure(null);
    }

    @Test
    void shouldBuildApkUrlsFromVersionCodeAndBuildCode() {
        assertEquals(
                "https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/1.12.1_cmschina_armeabi_b1843/cms_yht_32.apk",
                ApkDownloadUtil.buildApk32Url("1.12.1", "b1843")
        );
        assertEquals(
                "https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/release-20260609_cmschina_arm64_b1843/cms_yht_64.apk",
                ApkDownloadUtil.buildApk64Url("release-20260609", "b1843")
        );
    }

    @Test
    void shouldRejectBlankBuildCode() {
        assertThrows(IllegalArgumentException.class, () -> ApkDownloadUtil.buildApk32Url("101", " "));
    }

    @Test
    void shouldUseConfiguredTemplatesAndAuthorization() {
        AppProperties.PackageRepositoryProperties properties = new AppProperties.PackageRepositoryProperties();
        properties.setApkUrl32("https://download.example.com/apk/%s/%s/32.apk");
        properties.setApkUrl64("https://download.example.com/apk/%s/%s/64.apk");
        properties.setAuthorization("Basic custom-token");

        ApkDownloadUtil.configure(properties);

        assertEquals(
                "https://download.example.com/apk/1.12.1/b1843/32.apk",
                ApkDownloadUtil.buildApk32Url("1.12.1", "b1843")
        );
        assertEquals(
                "https://download.example.com/apk/release-20260609/b1843/64.apk",
                ApkDownloadUtil.buildApk64Url("release-20260609", "b1843")
        );
        assertEquals("Basic custom-token", ApkDownloadUtil.defaultAuthorizationValue());
    }

    @Test
    void shouldExtractOriginalFileNameFromDownloadUrl() {
        assertEquals(
                "cms_yht_32.apk",
                ApkDownloadUtil.extractFileName("https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/1.12.1_cmschina_armeabi_b1843/cms_yht_32.apk")
        );
    }

    @Test
    void shouldSkipDownloadWhenTargetFileAlreadyExists() throws IOException {
        Path tempFile = Files.createTempFile("apk-download-util-", ".apk");
        Files.writeString(tempFile, "existing");

        ApkDownloadUtil.downloadApk32("101", "b1843", tempFile.toString());

        assertEquals("existing", Files.readString(tempFile));
    }
}
