package com.app.publishservice.api;

import com.app.publishservice.api.dto.AppResponse;
import com.app.publishservice.api.dto.AppUpsertRequest;
import com.app.publishservice.api.dto.PackageUploadResponse;
import com.app.publishservice.api.dto.ReleaseRecordResponse;
import com.app.publishservice.api.dto.ReleaseSubmitRequest;
import com.app.publishservice.api.dto.StoreConfigRequest;
import com.app.publishservice.service.AppManagementService;
import com.app.publishservice.service.PackageVersionService;
import com.app.publishservice.service.ReleaseOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppManagementService appManagementService;

    @Autowired
    private PackageVersionService packageVersionService;

    @Autowired
    private ReleaseOrchestrationService releaseOrchestrationService;

    /**
     * 测试Expose Swagger Ui OpenAPI Yaml场景。
     */
    @Test
    void shouldExposeSwaggerUiAndOpenapiYaml() throws Exception {
        MvcResult openapiResult = mockMvc.perform(get("/openapi.yaml"))
                .andExpect(status().isOk())
                .andReturn();
        String openapiYaml = new String(openapiResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(openapiYaml.contains("openapi: 3.0.3"));
        assertTrue(openapiYaml.contains("url: /"));
        assertFalse(openapiYaml.contains("http://localhost:8080"));
        assertTrue(openapiYaml.contains("components:"));
        assertTrue(openapiYaml.contains("AppVersionResponse"));
        assertTrue(openapiYaml.contains("StoreConfigRequest"));

        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * 测试Expose 查询 Apis 应用版本发布场景。
     */
    @Test
    void shouldExposeQueryApisForAppVersionAndRelease() throws Exception {
        AppUpsertRequest appRequest = new AppUpsertRequest();
        appRequest.setAppName("Demo Query App");
        appRequest.setPackageName("com.demo.query.app");
        appRequest.setAppType(1);
        AppResponse app = appManagementService.saveApp(appRequest);

        StoreConfigRequest storeConfigRequest = new StoreConfigRequest();
        storeConfigRequest.setStoreType("huawei");
        storeConfigRequest.setClientId("demo-client");
        storeConfigRequest.setClientSecret("demo-secret");
        storeConfigRequest.setToken("demo-token");
        Long firstConfigId = appManagementService.saveStoreConfig(storeConfigRequest).id();

        mockMvc.perform(put("/api/apps/{appId}", app.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "appName": "Demo Query App Updated",
                                  "packageName": "com.demo.query.app",
                                  "appType": 1,
                                  "appDescription": "updated description",
                                  "status": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appName").value("Demo Query App Updated"))
                .andExpect(jsonPath("$.data.appDescription").value("updated description"));

        mockMvc.perform(get("/api/store-configs/{configId}", firstConfigId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientSecret").value("demo-secret"));

        mockMvc.perform(put("/api/store-configs/{configId}", firstConfigId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeType": "huawei",
                                  "accountName": "main-account",
                                  "email": "main@example.com",
                                  "phone": "13800138000",
                                  "clientId": "demo-client-updated",
                                  "clientSecret": "demo-secret-updated",
                                  "publicKey": "public-key",
                                  "privateKey": "private-key",
                                  "token": "demo-token-updated",
                                  "ipWhitelist": "127.0.0.1",
                                  "apiStatus": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountName").value("main-account"))
                .andExpect(jsonPath("$.data.clientId").value("demo-client-updated"));

        mockMvc.perform(put("/api/store-configs/{configId}/status", firstConfigId)
                        .param("apiStatus", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiStatus").value(0));

        mockMvc.perform(put("/api/store-configs/{configId}/status", firstConfigId)
                        .param("apiStatus", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiStatus").value(1));

        mockMvc.perform(post("/api/store-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeType": "oppo",
                                  "accountName": "backup-account",
                                  "email": "backup@example.com",
                                  "phone": "13900139000",
                                  "clientId": "oppo-client",
                                  "clientSecret": "oppo-secret",
                                  "token": "oppo-token",
                                  "ipWhitelist": "10.0.0.1",
                                  "apiStatus": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeType").value("oppo"))
                .andExpect(jsonPath("$.data.accountName").value("backup-account"));

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "demo-query-1.0.1-build101.apk",
                "application/octet-stream",
                buildArchive("1.0.1", "release-101", false)
        );
        PackageUploadResponse uploadResponse = packageVersionService.upload(
                app.id(),
                multipartFile,
                "query api test",
                "1.0.1",
                "release-101",
                false
        );

        ReleaseSubmitRequest submitRequest = new ReleaseSubmitRequest();
        submitRequest.setVersionId(uploadResponse.versionId());
        submitRequest.setStoreTypes(List.of("huawei"));
        submitRequest.setReleaseMode("api");
        ReleaseRecordResponse releaseRecord = releaseOrchestrationService.submit(submitRequest).get(0);

        mockMvc.perform(get("/api/apps").param("keyword", "Query App"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(app.id()))
                .andExpect(jsonPath("$.data[0].appName").value("Demo Query App Updated"))
                .andExpect(jsonPath("$.data[0].versions[0].versionCode").value("release-101"))
                .andExpect(jsonPath("$.data[0].releaseRecords[0].appName").value("Demo Query App Updated"))
                .andExpect(jsonPath("$.data[0].releaseRecords[0].storeType").value("huawei"));

        mockMvc.perform(get("/api/apps/{appId}", app.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(app.id()))
                .andExpect(jsonPath("$.data.appName").value("Demo Query App Updated"))
                .andExpect(jsonPath("$.data.packageName").value("com.demo.query.app"))
                .andExpect(jsonPath("$.data.appDescription").value("updated description"))
                .andExpect(jsonPath("$.data.versions[0].versionCode").value("release-101"))
                .andExpect(jsonPath("$.data.releaseRecords[0].appName").value("Demo Query App Updated"))
                .andExpect(jsonPath("$.data.releaseRecords[0].packageName").value("com.demo.query.app"))
                .andExpect(jsonPath("$.data.releaseRecords[0].appDescription").value("updated description"))
                .andExpect(jsonPath("$.data.releaseRecords[0].versionCode").value("release-101"))
                .andExpect(jsonPath("$.data.releaseRecords[0].storeType").value("huawei"))
                .andExpect(jsonPath("$.data.releaseRecords[0].releaseType").value(1))
                .andExpect(jsonPath("$.data.releaseRecords[0].grayPercent").isEmpty());

        mockMvc.perform(get("/api/store-configs")
                        .param("current", "1")
                        .param("size", "1")
                        .param("key", "main-account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pages").value(1))
                .andExpect(jsonPath("$.data.records[0].storeType").value("huawei"))
                .andExpect(jsonPath("$.data.records[0].token").value("demo-token-updated"));

        mockMvc.perform(get("/api/apps/{appId}/versions", app.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(uploadResponse.versionId()))
                .andExpect(jsonPath("$.data[0].versionCode").value("release-101"))
                .andExpect(jsonPath("$.data[0].packageUrl32").value(uploadResponse.storedPath()))
                .andExpect(jsonPath("$.data[0].packageUrl64").isEmpty());

        mockMvc.perform(get("/api/apps/{appId}/versions/{versionId}", app.id(), uploadResponse.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionName").value("1.0.1"))
                .andExpect(jsonPath("$.data.reinforced").value(false));

        mockMvc.perform(get("/api/releases")
                        .param("current", "1")
                        .param("size", "10")
                        .param("appId", app.id().toString())
                        .param("versionId", uploadResponse.versionId().toString())
                        .param("storeType", "huawei")
                        .param("releaseStatus", "auditing")
                        .param("key", "release-101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pages").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(releaseRecord.id()))
                .andExpect(jsonPath("$.data.records[0].appName").value("Demo Query App Updated"))
                .andExpect(jsonPath("$.data.records[0].packageName").value("com.demo.query.app"))
                .andExpect(jsonPath("$.data.records[0].appDescription").value("updated description"))
                .andExpect(jsonPath("$.data.records[0].versionCode").value("release-101"))
                .andExpect(jsonPath("$.data.records[0].storeType").value("huawei"))
                .andExpect(jsonPath("$.data.records[0].releaseStatus").value("reject"));

        mockMvc.perform(get("/api/releases/{releaseId}", releaseRecord.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(releaseRecord.id()))
                .andExpect(jsonPath("$.data.appName").value("Demo Query App Updated"))
                .andExpect(jsonPath("$.data.packageName").value("com.demo.query.app"))
                .andExpect(jsonPath("$.data.appDescription").value("updated description"))
                .andExpect(jsonPath("$.data.versionCode").value("release-101"))
                .andExpect(jsonPath("$.data.storeType").value("huawei"))
                .andExpect(jsonPath("$.data.releaseType").value(1))
                .andExpect(jsonPath("$.data.grayPercent").isEmpty());

        Long secondConfigId = appManagementService.getStoreConfigResponses().stream()
                .filter(config -> "oppo".equals(config.storeType()))
                .findFirst()
                .orElseThrow()
                .id();

        mockMvc.perform(delete("/api/store-configs/{configId}", secondConfigId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(delete("/api/apps/{appId}", app.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/apps/{appId}", app.id()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/store-configs/{configId}", firstConfigId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeType").value("huawei"));
    }

    /**
     * 测试Initialize 应用版本 When Save 应用 Contains 版本编码场景。
     */
    @Test
    void shouldInitializeAppVersionWhenSaveAppContainsVersionCode() throws Exception {
        AppUpsertRequest request = new AppUpsertRequest();
        request.setAppName("Demo Init Version App");
        request.setPackageName("com.demo.init.version.app");
        request.setAppType(1);
        request.setVersionCode("release-100");
        request.setBuildCode("b1843");

        AppResponse app = appManagementService.saveApp(request);

        mockMvc.perform(get("/api/apps/{appId}", app.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(app.id()))
                .andExpect(jsonPath("$.data.versions[0].versionCode").value("release-100"))
                .andExpect(jsonPath("$.data.versions[0].versionName").value("release-100"))
                .andExpect(jsonPath("$.data.versions[0].buildCode").value("b1843"))
                .andExpect(jsonPath("$.data.versions[0].updateLog").value("创建应用时自动初始化版本记录"))
                .andExpect(jsonPath("$.data.versions[0].packageUrl").isEmpty())
                .andExpect(jsonPath("$.data.versions[0].packageUrl32").isEmpty())
                .andExpect(jsonPath("$.data.versions[0].packageUrl64").isEmpty());

        mockMvc.perform(put("/api/apps/{appId}", app.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "appName": "Demo Init Version App",
                                  "packageName": "com.demo.init.version.app",
                                  "appType": 1,
                                  "versionCode": "release-100",
                                  "buildCode": "b1844"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/apps/{appId}", app.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions[0].versionCode").value("release-100"))
                .andExpect(jsonPath("$.data.versions[0].versionName").value("release-100"))
                .andExpect(jsonPath("$.data.versions[0].buildCode").value("b1844"));
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
