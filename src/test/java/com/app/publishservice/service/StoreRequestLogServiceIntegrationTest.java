package com.app.publishservice.service;

import com.app.publishservice.domain.entity.AppInfo;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppStoreRequestLog;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.AppType;
import com.app.publishservice.domain.enums.ReleaseMode;
import com.app.publishservice.domain.enums.ReleaseStatus;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.repository.AppInfoRepository;
import com.app.publishservice.repository.AppReleaseRecordRepository;
import com.app.publishservice.repository.AppStoreConfigRepository;
import com.app.publishservice.repository.AppStoreRequestLogRepository;
import com.app.publishservice.repository.AppVersionRepository;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class StoreRequestLogServiceIntegrationTest {

    @Autowired
    private StoreRequestLogService storeRequestLogService;

    @Autowired
    private AppStoreRequestLogRepository storeRequestLogRepository;

    @Autowired
    private AppStoreConfigRepository storeConfigRepository;

    @Autowired
    private AppInfoRepository appInfoRepository;

    @Autowired
    private AppVersionRepository appVersionRepository;

    @Autowired
    private AppReleaseRecordRepository releaseRecordRepository;

    @Test
    void shouldPersistMaskedOrderedStoreRequestLogs() {
        AppStoreConfig storeConfig = createStoreConfig();
        AppReleaseRecord releaseRecord = createReleaseRecord();

        try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(releaseRecord.getId())) {
            storeRequestLogService.logSuccess(
                    storeConfig,
                    "request oppo endpoint /resource/v1/upload/get-upload-url",
                    "GET",
                    "https://example.test/resource/v1/upload/get-upload-url",
                    Map.of("access_token", "plain-token", "pkg_name", "com.demo.log"),
                    null,
                    200,
                    "{\"data\":{\"token\":\"response-token\",\"status\":\"ok\"}}",
                    18L
            );
            storeRequestLogService.logFailure(
                    storeConfig,
                    "request oppo endpoint /resource/v1/app/task-state",
                    "POST",
                    "https://example.test/resource/v1/app/task-state",
                    null,
                    Map.of("api_sign", "signed-value", "pkg_name", "com.demo.log"),
                    502,
                    "{\"message\":\"bad gateway\"}",
                    "upstream bad gateway",
                    31L
            );
        }

        List<AppStoreRequestLog> logs = storeRequestLogRepository.selectList(
                Wrappers.<AppStoreRequestLog>lambdaQuery()
                        .eq(AppStoreRequestLog::getReleaseRecordId, releaseRecord.getId())
                        .orderByAsc(AppStoreRequestLog::getRequestOrder)
                        .orderByAsc(AppStoreRequestLog::getId)
        );

        assertEquals(2, logs.size());
        assertEquals(1L, logs.get(0).getRequestOrder());
        assertEquals(2L, logs.get(1).getRequestOrder());
        assertEquals("SUCCESS", logs.get(0).getRequestStatus());
        assertEquals("FAILED", logs.get(1).getRequestStatus());
        assertTrue(logs.get(0).getRequestParams().contains("\"access_token\":\"***\""));
        assertTrue(logs.get(0).getResponseBody().contains("\"token\":\"***\""));
        assertTrue(logs.get(1).getRequestBody().contains("\"api_sign\":\"***\""));
        assertEquals("upstream bad gateway", logs.get(1).getErrorMessage());
    }

    private AppStoreConfig createStoreConfig() {
        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode("oppo"));
        storeConfig.setClientId("demo-client-id");
        storeConfig.setClientSecret("demo-client-secret");
        storeConfig.setApiStatus(1);
        storeConfigRepository.insert(storeConfig);
        return storeConfig;
    }

    private AppReleaseRecord createReleaseRecord() {
        AppInfo appInfo = new AppInfo();
        appInfo.setAppName("Demo Log App");
        appInfo.setPackageName("com.demo.log");
        appInfo.setAppType(AppType.ANDROID);
        appInfo.setStatus(1);
        appInfoRepository.insert(appInfo);

        AppVersion version = new AppVersion();
        version.setAppId(appInfo.getId());
        version.setAppInfo(appInfo);
        version.setVersionName("1.0.0");
        version.setVersionCode("100");
        version.setPackageUrl("storage/demo.apk");
        version.setIsReinforce(0);
        appVersionRepository.insert(version);

        AppReleaseRecord releaseRecord = new AppReleaseRecord();
        releaseRecord.setAppId(appInfo.getId());
        releaseRecord.setVersionId(version.getId());
        releaseRecord.setStoreType(StoreType.fromCode("oppo"));
        releaseRecord.setReleaseMode(ReleaseMode.API);
        releaseRecord.setReleaseStatus(ReleaseStatus.API_PENDING);
        releaseRecordRepository.insert(releaseRecord);
        return releaseRecord;
    }
}
