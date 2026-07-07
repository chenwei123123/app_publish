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

    /**
     * 处理tear Down相关逻辑。
     */
    @AfterEach
    void tearDown() {
        ApkDownloadUtil.configure(null);
    }

    /**
     * 测试Build APK Urls 版本编码 Build 编码场景。
     */
    @Test
    void shouldBuildApkUrlsFromVersionCodeAndBuildCode() {
        assertEquals(
                "https://artifacts.cmschina.com.cn:443/artifactory/cms_app-generic-release-wx/android/1.12.1_cmschina_armeabi_b1843/cms_yht_32.apk",
                ApkDownloadUtil.buildApk32Url("1.12.1", "b1843")
        );
        assertEquals(
                "https://artifacts.cmschina.com.cn:443/artifactory/cms_app-generic-release-wx/android/release-20260609_cmschina_arm64_b1843/cms_yht_64.apk",
                ApkDownloadUtil.buildApk64Url("release-20260609", "b1843")
        );
        assertEquals(
                "https://artifacts.cmschina.com.cn:443/artifactory/INT_CMSAPP_HARMONY-generic-release-wx/harmony/1.12.1_b1843/CMSApp_HM-yht_release-signed.app",
                ApkDownloadUtil.buildAppUrl("1.12.1", "b1843")
        );
    }

    /**
     * 测试Reject Blank Build 编码场景。
     */
    @Test
    void shouldRejectBlankBuildCode() {
        assertThrows(IllegalArgumentException.class, () -> ApkDownloadUtil.buildApk32Url("101", " "));
    }

    /**
     * 测试Use Configured Templates 授权场景。
     */
    @Test
    void shouldUseConfiguredTemplatesAndAuthorization() {
        AppProperties.PackageRepositoryProperties properties = new AppProperties.PackageRepositoryProperties();
        properties.setApkUrl32("https://download.example.com/apk/%s/%s/32.apk");
        properties.setApkUrl64("https://download.example.com/apk/%s/%s/64.apk");
        properties.setAppUrl("https://download.example.com/app/%s/%s/release.app");
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
        assertEquals(
                "https://download.example.com/app/release-20260609/b1843/release.app",
                ApkDownloadUtil.buildAppUrl("release-20260609", "b1843")
        );
        assertEquals("Basic custom-token", ApkDownloadUtil.defaultAuthorizationValue());
    }

    /**
     * 测试Extract Original 文件名称下载 URL场景。
     */
    @Test
    void shouldExtractOriginalFileNameFromDownloadUrl() {
        assertEquals(
                "cms_yht_32.apk",
                ApkDownloadUtil.extractFileName("https://artifacts.cmschina.com.cn:443/artifactory/cms_app-generic-release-wx/android/1.12.1_cmschina_armeabi_b1843/cms_yht_32.apk")
        );
    }

    /**
     * 测试Skip 下载 When Target 文件 Already Exists场景。
     */
    @Test
    void shouldSkipDownloadWhenTargetFileAlreadyExists() throws Exception {
        Path tempFile = Files.createTempFile("apk-download-util-", ".apk");
        Files.writeString(tempFile, "existing");

        ApkDownloadUtil.downloadApk32("101", "b1843", tempFile.toString());

        assertEquals("existing", Files.readString(tempFile));
    }
}
