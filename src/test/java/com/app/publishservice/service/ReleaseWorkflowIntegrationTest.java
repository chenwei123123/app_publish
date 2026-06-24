package com.app.publishservice.service;

import com.app.publishservice.api.dto.AppResponse;
import com.app.publishservice.api.dto.AppUpsertRequest;
import com.app.publishservice.api.dto.PackageUploadResponse;
import com.app.publishservice.api.dto.ReleaseRecordResponse;
import com.app.publishservice.api.dto.ReleaseSubmitRequest;
import com.app.publishservice.api.dto.StoreConfigRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
class ReleaseWorkflowIntegrationTest {

    @Autowired
    private AppManagementService appManagementService;

    @Autowired
    private PackageVersionService packageVersionService;

    @Autowired
    private ReleaseOrchestrationService releaseOrchestrationService;

    /**
     * 测试上传提交 Poll 发布场景。
     */
    @Test
    void shouldUploadSubmitAndPollRelease() throws Exception {
        AppUpsertRequest appRequest = new AppUpsertRequest();
        appRequest.setAppName("Demo App");
        appRequest.setPackageName("com.demo.app");
        appRequest.setAppType(1);
        AppResponse app = appManagementService.saveApp(appRequest);

        StoreConfigRequest storeConfigRequest = new StoreConfigRequest();
        storeConfigRequest.setStoreType("huawei");
        storeConfigRequest.setClientId("demo-client");
        storeConfigRequest.setClientSecret("demo-secret");
        appManagementService.saveStoreConfig(storeConfigRequest);

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "demo-1.0.1-build101-reinforced.apk",
                "application/octet-stream",
                buildArchive("1.0.1", "101", true)
        );

        PackageUploadResponse uploadResponse = packageVersionService.upload(
                app.id(),
                multipartFile,
                "bug fixes",
                "1.0.1",
                "101",
                true
        );

        ReleaseSubmitRequest submitRequest = new ReleaseSubmitRequest();
        submitRequest.setVersionId(uploadResponse.versionId());
        submitRequest.setStoreTypes(List.of("huawei"));
        submitRequest.setReleaseMode("api");
        List<ReleaseRecordResponse> releaseRecords = releaseOrchestrationService.submit(submitRequest);
        assertEquals("auditing", releaseRecords.get(0).releaseStatus());

        Thread.sleep(1100);
        releaseOrchestrationService.pollAuditResults();
        ReleaseRecordResponse refreshed = releaseOrchestrationService.getReleaseRecord(releaseRecords.get(0).id());
        assertEquals("pass", refreshed.releaseStatus());
    }

    /**
     * 构建Archive。
     */
    private byte[] buildArchive(String versionName, String versionCode, boolean reinforced) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            zipOutputStream.putNextEntry(new ZipEntry("app-publish-metadata.json"));
            String json = """
                    {
                      "versionName": "%s",
                      "versionCode": "%s",
                      "reinforced": %s
                    }
                    """.formatted(versionName, versionCode, reinforced);
            zipOutputStream.write(json.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return outputStream.toByteArray();
    }
}
