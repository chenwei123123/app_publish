package com.app.publishservice.service;

import com.app.publishservice.api.dto.ReleaseRecordResponse;
import com.app.publishservice.api.dto.ReleaseSubmitRequest;
import com.app.publishservice.common.exception.SubmitPackageDownloadException;
import com.app.publishservice.domain.entity.AppInfo;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.ReleaseMode;
import com.app.publishservice.domain.enums.ReleaseStatus;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.repository.AppReleaseRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleaseOrchestrationServiceTest {

    private static final String APK32_DOWNLOAD_FAILED_MESSAGE = "32\u4F4Dapk\u6587\u4EF6\u4E0B\u8F7D\u5931\u8D25\uFF0C\u8BF7\u6838\u5BF9\u7248\u672C\u53F7\u548C\u6784\u5EFA\u53F7";

    @Test
    void shouldRejectStagedReleaseForOppoAndXiaomi() {
        PackageVersionService packageVersionService = mock(PackageVersionService.class);
        AppVersion version = new AppVersion();
        version.setId(1L);
        version.setAppId(1L);
        version.setAppInfo(new AppInfo());
        when(packageVersionService.requireVersion(1L)).thenReturn(version);

        ReleaseOrchestrationService service = new ReleaseOrchestrationService(
                packageVersionService,
                mock(AppManagementService.class),
                mock(AppReleaseRecordRepository.class),
                mock(TokenService.class),
                mock(StorePublisher.class),
                Runnable::run,
                transactionOperations()
        );

        ReleaseSubmitRequest request = new ReleaseSubmitRequest();
        request.setVersionId(1L);
        request.setStoreTypes(List.of("oppo", "xiaomi"));
        request.setReleaseType(2L);
        request.setGrayPercent(20L);
        request.setGrayStartTime(LocalDateTime.of(2026, 6, 8, 10, 0));
        request.setGrayEndTime(LocalDateTime.of(2026, 6, 9, 10, 0));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.submit(request));
        assertEquals("oppo does not support staged release submit via API", exception.getMessage());
    }

    @Test
    void shouldRejectStagedReleaseForYingyongbao() {
        PackageVersionService packageVersionService = mock(PackageVersionService.class);
        AppVersion version = new AppVersion();
        version.setId(1L);
        version.setAppId(1L);
        version.setAppInfo(new AppInfo());
        when(packageVersionService.requireVersion(1L)).thenReturn(version);

        ReleaseOrchestrationService service = new ReleaseOrchestrationService(
                packageVersionService,
                mock(AppManagementService.class),
                mock(AppReleaseRecordRepository.class),
                mock(TokenService.class),
                mock(StorePublisher.class),
                Runnable::run,
                transactionOperations()
        );

        ReleaseSubmitRequest request = new ReleaseSubmitRequest();
        request.setVersionId(1L);
        request.setStoreTypes(List.of("yingyongbao"));
        request.setReleaseType(2L);
        request.setGrayPercent(20L);
        request.setGrayStartTime(LocalDateTime.of(2026, 6, 8, 10, 0));
        request.setGrayEndTime(LocalDateTime.of(2026, 6, 9, 10, 0));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.submit(request));
        assertEquals("yingyongbao does not support staged release submit via API", exception.getMessage());
    }

    @Test
    void shouldPersistDownloadFailureStatusAndRethrowMessage() {
        PackageVersionService packageVersionService = mock(PackageVersionService.class);
        AppManagementService appManagementService = mock(AppManagementService.class);
        AppReleaseRecordRepository releaseRecordRepository = mock(AppReleaseRecordRepository.class);

        AppVersion version = new AppVersion();
        version.setId(9L);
        version.setAppId(18L);
        version.setVersionCode("123");
        version.setBuildCode("b456");
        AppInfo appInfo = new AppInfo();
        appInfo.setId(18L);
        appInfo.setAppName("demo");
        appInfo.setPackageName("com.demo");
        appInfo.setAppDescription("desc");
        version.setAppInfo(appInfo);

        when(packageVersionService.requireVersion(9L)).thenReturn(version);
        when(packageVersionService.requiresSubmitPackageDownload(version)).thenReturn(true);
        when(packageVersionService.prepareVersionForSubmit(version))
                .thenThrow(new SubmitPackageDownloadException(APK32_DOWNLOAD_FAILED_MESSAGE, new RuntimeException("download failed")));

        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode("huawei"));
        storeConfig.setApiStatus(1);
        when(appManagementService.requireStoreConfig(StoreType.fromCode("huawei"))).thenReturn(storeConfig);

        AtomicLong idSequence = new AtomicLong(100L);
        doAnswer(invocation -> {
            AppReleaseRecord record = invocation.getArgument(0);
            record.setId(idSequence.getAndIncrement());
            return 1;
        }).when(releaseRecordRepository).insert(any(AppReleaseRecord.class));

        ReleaseOrchestrationService service = new ReleaseOrchestrationService(
                packageVersionService,
                appManagementService,
                releaseRecordRepository,
                mock(TokenService.class),
                mock(StorePublisher.class),
                Runnable::run,
                transactionOperations()
        );

        ReleaseSubmitRequest request = new ReleaseSubmitRequest();
        request.setVersionId(9L);
        request.setStoreTypes(List.of("huawei"));
        request.setReleaseMode("api");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.submit(request));
        assertEquals(APK32_DOWNLOAD_FAILED_MESSAGE, exception.getMessage());

        ArgumentCaptor<AppReleaseRecord> updateCaptor = ArgumentCaptor.forClass(AppReleaseRecord.class);
        verify(releaseRecordRepository).insert(any(AppReleaseRecord.class));
        verify(releaseRecordRepository).updateById(updateCaptor.capture());
        AppReleaseRecord failedRecord = updateCaptor.getValue();
        assertEquals(ReleaseStatus.DOWNLOAD_FAIL, failedRecord.getReleaseStatus());
        assertEquals(APK32_DOWNLOAD_FAILED_MESSAGE, failedRecord.getRejectReason());
        assertEquals(APK32_DOWNLOAD_FAILED_MESSAGE, failedRecord.getApiResponseLog());
        assertNotNull(failedRecord.getFinishTime());
    }

    @Test
    void shouldSubmitStoresInParallelAndKeepFailuresIsolated() throws Exception {
        PackageVersionService packageVersionService = mock(PackageVersionService.class);
        AppManagementService appManagementService = mock(AppManagementService.class);
        AppReleaseRecordRepository releaseRecordRepository = mock(AppReleaseRecordRepository.class);
        TokenService tokenService = mock(TokenService.class);
        StorePublisher storePublisher = mock(StorePublisher.class);

        AppVersion version = new AppVersion();
        version.setId(9L);
        version.setAppId(18L);
        version.setVersionCode("123");
        AppInfo appInfo = new AppInfo();
        appInfo.setId(18L);
        appInfo.setAppName("demo");
        appInfo.setPackageName("com.demo");
        appInfo.setAppDescription("desc");
        version.setAppInfo(appInfo);

        when(packageVersionService.requireVersion(9L)).thenReturn(version);
        when(packageVersionService.prepareVersionForSubmit(version)).thenReturn(version);

        AppStoreConfig huaweiConfig = new AppStoreConfig();
        huaweiConfig.setId(1L);
        huaweiConfig.setStoreType(StoreType.fromCode("huawei"));
        huaweiConfig.setApiStatus(1);
        AppStoreConfig oppoConfig = new AppStoreConfig();
        oppoConfig.setId(2L);
        oppoConfig.setStoreType(StoreType.fromCode("oppo"));
        oppoConfig.setApiStatus(1);
        when(appManagementService.requireStoreConfig(StoreType.fromCode("huawei"))).thenReturn(huaweiConfig);
        when(appManagementService.requireStoreConfig(StoreType.fromCode("oppo"))).thenReturn(oppoConfig);

        when(tokenService.getValidToken(huaweiConfig)).thenReturn("token-huawei");
        when(tokenService.getValidToken(oppoConfig)).thenReturn("token-oppo");

        AtomicLong idSequence = new AtomicLong(100L);
        doAnswer(invocation -> {
            AppReleaseRecord record = invocation.getArgument(0);
            record.setId(idSequence.getAndIncrement());
            return 1;
        }).when(releaseRecordRepository).insert(any(AppReleaseRecord.class));

        CountDownLatch huaweiStarted = new CountDownLatch(1);
        CountDownLatch releaseHuawei = new CountDownLatch(1);
        CountDownLatch oppoFinished = new CountDownLatch(1);

        when(storePublisher.submitRelease(any(), any(), any(), any())).thenAnswer(invocation -> {
            AppStoreConfig storeConfig = invocation.getArgument(0);
            if (storeConfig.getStoreType() == StoreType.fromCode("huawei")) {
                huaweiStarted.countDown();
                assertTrue(releaseHuawei.await(2, TimeUnit.SECONDS));
                return new com.app.publishservice.service.model.StoreSubmitResult("hw-release", "hw-req", "hw-resp", null);
            }
            assertTrue(huaweiStarted.await(2, TimeUnit.SECONDS));
            oppoFinished.countDown();
            releaseHuawei.countDown();
            throw new IllegalStateException("oppo failed");
        });

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            ReleaseOrchestrationService service = new ReleaseOrchestrationService(
                    packageVersionService,
                    appManagementService,
                    releaseRecordRepository,
                    tokenService,
                    storePublisher,
                    executorService,
                    transactionOperations()
            );

            ReleaseSubmitRequest request = new ReleaseSubmitRequest();
            request.setVersionId(9L);
            request.setStoreTypes(List.of("huawei", "oppo"));
            request.setReleaseMode(ReleaseMode.API.getCode());

            List<ReleaseRecordResponse> responses = service.submit(request);

            assertTrue(oppoFinished.await(1, TimeUnit.SECONDS));
            assertEquals(2, responses.size());
            assertEquals("huawei", responses.get(0).storeType());
            assertEquals("auditing", responses.get(0).releaseStatus());
            assertEquals("hw-release", responses.get(0).storeReleaseId());
            assertEquals("oppo", responses.get(1).storeType());
            assertEquals("reject", responses.get(1).releaseStatus());
            assertEquals("oppo failed", responses.get(1).rejectReason());
        } finally {
            executorService.shutdownNow();
            executorService.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private TransactionOperations transactionOperations() {
        TransactionOperations transactionOperations = mock(TransactionOperations.class);
        lenient().when(transactionOperations.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        return transactionOperations;
    }
}
