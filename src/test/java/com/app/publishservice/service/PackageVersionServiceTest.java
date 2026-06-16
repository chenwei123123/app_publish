package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.repository.AppVersionRepository;
import com.app.publishservice.util.ApkDownloadUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageVersionServiceTest {

    private static final String APK32_DOWNLOAD_FAILED_MESSAGE = "32\u4F4Dapk\u6587\u4EF6\u4E0B\u8F7D\u5931\u8D25\uFF0C\u8BF7\u6838\u5BF9\u7248\u672C\u53F7\u548C\u6784\u5EFA\u53F7";
    private static final String APK64_DOWNLOAD_FAILED_MESSAGE = "64\u4F4Dapk\u6587\u4EF6\u4E0B\u8F7D\u5931\u8D25\uFF0C\u8BF7\u6838\u5BF9\u7248\u672C\u53F7\u548C\u6784\u5EFA\u53F7";
    private static final String APK32_LOCAL_FILE_FAILED_MESSAGE = "32\u4F4Dapk\u672C\u5730\u6587\u4EF6\u8BFB\u53D6\u5931\u8D25\uFF0C\u8BF7\u6838\u5BF9application.yml\u4E2D\u7684app.publish-metadata.values.apk32Path";

    /**
     * 测试Prepare Remote 提交包 Urls When 流上传 Enabled场景。
     */
    @Test
    void shouldPrepareRemoteSubmitPackageUrlsWhenStreamUploadEnabled() {
        AppProperties appProperties = new AppProperties();
        appProperties.getPackageRepository().setStreamUploadEnabled(true);
        AppVersionRepository repository = mock(AppVersionRepository.class);
        Environment environment = mock(Environment.class);
        when(environment.matchesProfiles("dev")).thenReturn(false);
        when(repository.updateById(any(AppVersion.class))).thenReturn(1);

        PackageVersionService service = new PackageVersionService(
                mock(AppManagementService.class),
                repository,
                mock(StorageService.class),
                mock(PackageInspectorService.class),
                appProperties,
                environment
        );

        AppVersion version = new AppVersion();
        version.setId(9L);
        version.setVersionCode("123");
        version.setBuildCode("b456");

        AppVersion prepared = service.prepareVersionForSubmit(version);

        assertEquals("https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/123_cmschina_armeabi_b456/cms_yht_32.apk", prepared.getPackageUrl32());
        assertEquals("https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/123_cmschina_arm64_b456/cms_yht_64.apk", prepared.getPackageUrl64());
        verify(repository).updateById(version);
    }

    /**
     * 测试Throw Exact 消息 When Download32 Fails场景。
     */
    @Test
    void shouldThrowExactMessageWhenDownload32Fails() throws IOException {
        AppProperties appProperties = new AppProperties();
        StorageService storageService = mock(StorageService.class);
        AppVersionRepository repository = mock(AppVersionRepository.class);
        Environment environment = mock(Environment.class);
        when(environment.matchesProfiles("dev")).thenReturn(false);
        Path apk32Target = Path.of("target", "submit-32.apk");
        Path apk64Target = Path.of("target", "submit-64.apk");
        when(storageService.allocateDownloadPath(anyString(), anyString(), anyString()))
                .thenReturn(apk32Target)
                .thenReturn(apk64Target);

        PackageVersionService service = new PackageVersionService(
                mock(AppManagementService.class),
                repository,
                storageService,
                mock(PackageInspectorService.class),
                appProperties,
                environment
        );

        AppVersion version = new AppVersion();
        version.setId(9L);
        version.setVersionName("1.0.1");
        version.setVersionCode("123");
        version.setBuildCode("b456");

        try (MockedStatic<ApkDownloadUtil> mockedStatic = mockStatic(ApkDownloadUtil.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> ApkDownloadUtil.downloadApk32("123", "b456", apk32Target.toString()))
                    .thenThrow(new IOException("32 failed"));

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.prepareVersionForSubmit(version)
            );

            assertEquals(APK32_DOWNLOAD_FAILED_MESSAGE, exception.getMessage());
        }

        verify(storageService).allocateDownloadPath("123", "b456", "cms_yht_32.apk");
        verify(storageService).allocateDownloadPath("123", "b456", "cms_yht_64.apk");
        verify(repository, never()).updateById(any(AppVersion.class));
    }

    /**
     * 测试Throw Exact 消息 When Download64 Fails场景。
     */
    @Test
    void shouldThrowExactMessageWhenDownload64Fails() throws IOException {
        AppProperties appProperties = new AppProperties();
        StorageService storageService = mock(StorageService.class);
        AppVersionRepository repository = mock(AppVersionRepository.class);
        Environment environment = mock(Environment.class);
        when(environment.matchesProfiles("dev")).thenReturn(false);
        Path apk32Target = Path.of("target", "submit-32.apk");
        Path apk64Target = Path.of("target", "submit-64.apk");
        when(storageService.allocateDownloadPath(anyString(), anyString(), anyString()))
                .thenReturn(apk32Target)
                .thenReturn(apk64Target);

        PackageVersionService service = new PackageVersionService(
                mock(AppManagementService.class),
                repository,
                storageService,
                mock(PackageInspectorService.class),
                appProperties,
                environment
        );

        AppVersion version = new AppVersion();
        version.setId(9L);
        version.setVersionName("1.0.1");
        version.setVersionCode("123");
        version.setBuildCode("b456");

        try (MockedStatic<ApkDownloadUtil> mockedStatic = mockStatic(ApkDownloadUtil.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> ApkDownloadUtil.downloadApk32("123", "b456", apk32Target.toString()))
                    .thenAnswer(invocation -> null);
            mockedStatic.when(() -> ApkDownloadUtil.downloadApk64("123", "b456", apk64Target.toString()))
                    .thenThrow(new IOException("64 failed"));

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.prepareVersionForSubmit(version)
            );

            assertEquals(APK64_DOWNLOAD_FAILED_MESSAGE, exception.getMessage());
        }

        verify(storageService).allocateDownloadPath("123", "b456", "cms_yht_32.apk");
        verify(storageService).allocateDownloadPath("123", "b456", "cms_yht_64.apk");
        verify(repository, never()).updateById(any(AppVersion.class));
    }

    /**
     * 测试Resolve Local 提交包元数据 Dev场景。
     */
    @Test
    void shouldResolveLocalSubmitPackagesFromMetadataInDev(@TempDir Path tempDir) throws IOException {
        AppProperties appProperties = new AppProperties();
        AppVersionRepository repository = mock(AppVersionRepository.class);
        Environment environment = mock(Environment.class);
        when(environment.matchesProfiles("dev")).thenReturn(true);
        when(repository.updateById(any(AppVersion.class))).thenReturn(1);

        Path apk32 = Files.writeString(tempDir.resolve("cms_yht_32.apk"), "apk32");
        Path apk64 = Files.writeString(tempDir.resolve("cms_yht_64.apk"), "apk64");
        Path packageFile = Files.writeString(tempDir.resolve("origin.apk"), "origin");
        appProperties.getPublishMetadata().setBaseDir(tempDir.toString());
        appProperties.getPublishMetadata().setValues(Map.of(
                "vivo", Map.of(
                        "apk32Path", "cms_yht_32.apk",
                        "apk64Path", "cms_yht_64.apk"
                )
        ));

        PackageVersionService service = new PackageVersionService(
                mock(AppManagementService.class),
                repository,
                mock(StorageService.class),
                mock(PackageInspectorService.class),
                appProperties,
                environment
        );

        AppVersion version = new AppVersion();
        version.setId(9L);
        version.setVersionCode("123");
        version.setBuildCode("b456");
        version.setPackageUrl(packageFile.toString());

        AppVersion prepared = service.prepareVersionForSubmit(version);

        assertEquals(apk32.toString(), prepared.getPackageUrl32());
        assertEquals(apk64.toString(), prepared.getPackageUrl64());
        verify(repository).updateById(version);
    }

    /**
     * 测试Throw When Dev 元数据 Missing Apk32 路径场景。
     */
    @Test
    void shouldThrowWhenDevMetadataMissingApk32Path(@TempDir Path tempDir) throws IOException {
        AppProperties appProperties = new AppProperties();
        AppVersionRepository repository = mock(AppVersionRepository.class);
        Environment environment = mock(Environment.class);
        when(environment.matchesProfiles("dev")).thenReturn(true);

        Path packageFile = Files.writeString(tempDir.resolve("origin.apk"), "origin");
        appProperties.getPublishMetadata().setBaseDir(tempDir.toString());
        appProperties.getPublishMetadata().setValues(Map.of(
                "vivo", Map.of("apk64Path", "cms_yht_64.apk")
        ));
        assertNotNull(packageFile);

        PackageVersionService service = new PackageVersionService(
                mock(AppManagementService.class),
                repository,
                mock(StorageService.class),
                mock(PackageInspectorService.class),
                appProperties,
                environment
        );

        AppVersion version = new AppVersion();
        version.setId(9L);
        version.setVersionCode("123");
        version.setBuildCode("b456");
        version.setPackageUrl(packageFile.toString());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.prepareVersionForSubmit(version));
        assertEquals(APK32_LOCAL_FILE_FAILED_MESSAGE, exception.getMessage());
        verify(repository, never()).updateById(any(AppVersion.class));
    }
}
