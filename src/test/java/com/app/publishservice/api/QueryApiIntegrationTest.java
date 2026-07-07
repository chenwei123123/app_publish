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
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryApiIntegrationTest {

    private static final String TEST_XIAOMI_CERTIFICATE_BASE64 =
            "MIIDczCCAlugAwIBAgIINwvzQIQihIowDQYJKoZIhvcNAQEMBQAwaDELMAkGA1UEBhMCQ04xETAPBgNVBAgTCFNoYW5naGFpMREwDwYDVQQHEwhTaGFuZ2hhaTEQMA4GA1UEChMHRXhhbXBsZTELMAkGA1UECxMCUUExFDASBgNVBAMTC1Rlc3QgWGlhb21pMB4XDTI2MDcwNjAyNTM0N1oXDTM2MDcwMzAyNTM0N1owaDELMAkGA1UEBhMCQ04xETAPBgNVBAgTCFNoYW5naGFpMREwDwYDVQQHEwhTaGFuZ2hhaTEQMA4GA1UEChMHRXhhbXBsZTELMAkGA1UECxMCUUExFDASBgNVBAMTC1Rlc3QgWGlhb21pMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArW2HEMxHlAaYE5s/zLuhrOw7i4QKPBheHGjdvfFHOX940sbQOywPFYNjukcDmw968TReh+zlLMMorQ8Zr0NWlDTZ96Z1BLg2qvEs4QZpOpdY16WtpT4Lhe6IISVy+J1zcTO71Zq6VrlablRa+3ungmc49tzFyzP9xfb+QrOOq6viUkU1JMmD+rdUzV5BS8kckBbII7d5+yNLxAVSTD62POAlWt8ykCvfSMJYmRUsv5HDwkjXPtecLbTtWLuJmBgvKN3grWmMaynBGdpyND3dYeItA9fLT4Y6Kj6pLCvnIamsewPJ8qY05QtDmouMlK1mS+ll11q57SyvLOPHkWcUDwIDAQABoyEwHzAdBgNVHQ4EFgQUiIa0+L1Dop/l0RaEY0/+oMgT9AowDQYJKoZIhvcNAQEMBQADggEBABHIjEYO7ipV6AKU16WxGgfEKOKtycaDeB7QXssKaQhwqESGXwt12UU7nD74BkYt20BKd3sxi7oeM3x4MbfpSVIc4Ho6Vm6rNrWLu6SokV0Hp+yzTHP/dw8NHDxH55sy76zwUrx3Hmhfm9mGwBp5OWj4SKGaj3pcdsDmg947Ibe2ZD0sTb8I9xEMH6OSHaiTkPYWdY64kFiBRHcOkuf+GLiCZd3sxCfixUExw+0SU9siNWFsQu1FuAPfv6YbNR2aevnS4py70XCTgJchzj/+95ZjiRWwg45O2tku8wR9ehx1puNEQyoz11OslBDH3vzEdfGEnHLKCFuI8UHjVA1AT+w=";
    private static final String TEST_XIAOMI_PUBLIC_KEY_PEM = """
            -----BEGIN PUBLIC KEY-----
            MIIBCgKCAQEArW2HEMxHlAaYE5s/zLuhrOw7i4QKPBheHGjdvfFHOX940sbQOywP
            FYNjukcDmw968TReh+zlLMMorQ8Zr0NWlDTZ96Z1BLg2qvEs4QZpOpdY16WtpT4L
            he6IISVy+J1zcTO71Zq6VrlablRa+3ungmc49tzFyzP9xfb+QrOOq6viUkU1JMmD
            +rdUzV5BS8kckBbII7d5+yNLxAVSTD62POAlWt8ykCvfSMJYmRUsv5HDwkjXPtec
            LbTtWLuJmBgvKN3grWmMaynBGdpyND3dYeItA9fLT4Y6Kj6pLCvnIamsewPJ8qY0
            5QtDmouMlK1mS+ll11q57SyvLOPHkWcUDwIDAQAB
            -----END PUBLIC KEY-----
            """.stripIndent();

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
        assertTrue(openapiYaml.contains("StoreConfigMultipartRequest"));
        assertTrue(openapiYaml.contains("multipart/form-data"));
        assertTrue(openapiYaml.contains("publicKeyFile"));

        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs.yaml"));
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

        MockMultipartFile updateIcon = new MockMultipartFile(
                "iconFile",
                "xiaomi-icon.png",
                "image/png",
                "icon-bytes".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile updatePublicKey = new MockMultipartFile(
                "publicKeyFile",
                "xiaomi-public.pem",
                "application/octet-stream",
                "-----BEGIN PUBLIC KEY-----\nupdate-public-key\n-----END PUBLIC KEY-----".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/store-configs/{configId}", firstConfigId)
                        .file(updateIcon)
                        .file(updatePublicKey)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .param("storeType", "huawei")
                        .param("accountName", "main-account")
                        .param("email", "main@example.com")
                        .param("phone", "13800138000")
                        .param("clientId", "demo-client-updated")
                        .param("clientSecret", "demo-secret-updated")
                        .param("publicKey", "public-key")
                        .param("privateKey", "private-key")
                        .param("token", "demo-token-updated")
                        .param("ipWhitelist", "127.0.0.1")
                        .param("privacyUrl", "https://xiaomi.example.com/privacy")
                        .param("appId", "yyb-app-001")
                        .param("apiStatus", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountName").value("main-account"))
                .andExpect(jsonPath("$.data.clientId").value("demo-client-updated"))
                .andExpect(jsonPath("$.data.publicKey").value("-----BEGIN PUBLIC KEY-----\nupdate-public-key\n-----END PUBLIC KEY-----"))
                .andExpect(jsonPath("$.data.privacyUrl").value("https://xiaomi.example.com/privacy"))
                .andExpect(jsonPath("$.data.appId").value("yyb-app-001"))
                .andExpect(jsonPath("$.data.icon").value("aWNvbi1ieXRlcw=="));

        mockMvc.perform(put("/api/store-configs/{configId}/status", firstConfigId)
                        .param("apiStatus", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiStatus").value(0));

        mockMvc.perform(put("/api/store-configs/{configId}/status", firstConfigId)
                        .param("apiStatus", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiStatus").value(1));

        MockMultipartFile createIcon = new MockMultipartFile(
                "iconFile",
                "oppo-icon.png",
                "image/png",
                "create-icon".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile createPublicKey = new MockMultipartFile(
                "publicKeyFile",
                "create-public.pem",
                "application/octet-stream",
                "create-public-key".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/store-configs")
                        .file(createIcon)
                        .file(createPublicKey)
                        .param("storeType", "oppo")
                        .param("accountName", "backup-account")
                        .param("email", "backup@example.com")
                        .param("phone", "13900139000")
                        .param("clientId", "oppo-client")
                        .param("clientSecret", "oppo-secret")
                        .param("token", "oppo-token")
                        .param("ipWhitelist", "10.0.0.1")
                        .param("privacyUrl", "https://oppo.example.com/privacy")
                        .param("appId", "sanxing-content-001")
                        .param("apiStatus", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeType").value("oppo"))
                .andExpect(jsonPath("$.data.accountName").value("backup-account"))
                .andExpect(jsonPath("$.data.publicKey").value("create-public-key"))
                .andExpect(jsonPath("$.data.appId").value("sanxing-content-001"));

        mockMvc.perform(post("/api/store-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeType": "vivo",
                                  "accountName": "json-account",
                                  "email": "json@example.com",
                                  "icon": "data:image/png;base64,anNvbi1iYXNlNjQtaWNvbg==",
                                  "apiStatus": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeType").value("vivo"))
                .andExpect(jsonPath("$.data.icon").value("anNvbi1iYXNlNjQtaWNvbg=="));

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
                .andExpect(jsonPath("$.data.records[0].token").value("demo-token-updated"))
                .andExpect(jsonPath("$.data.records[0].privacyUrl").value("https://xiaomi.example.com/privacy"))
                .andExpect(jsonPath("$.data.records[0].appId").value("yyb-app-001"));

        mockMvc.perform(get("/api/apps/{appId}/versions", app.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(uploadResponse.versionId()))
                .andExpect(jsonPath("$.data[0].versionCode").value("release-101"))
                .andExpect(jsonPath("$.data[0].packageUrl32").value(uploadResponse.storedPath()))
                .andExpect(jsonPath("$.data[0].packageUrl64").isEmpty())
                .andExpect(jsonPath("$.data[0].packageAppUrl").isEmpty());

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
                .andExpect(jsonPath("$.data.records[0].releaseStatus").value("auditing"));

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
    void shouldCreateAndUpdateStoreConfigWithPublicKeyFile() throws Exception {
        StoreConfigRequest createRequest = new StoreConfigRequest();
        createRequest.setStoreType("xiaomi");
        createRequest.setAccountName("xiaomi-account");
        createRequest.setEmail("xiaomi@example.com");
        createRequest.setPublicKeyFile(new MockMultipartFile(
                "publicKeyFile",
                "xiaomi-create-public.pem",
                "application/octet-stream",
                "-----BEGIN PUBLIC KEY-----\ncreate-public-key\n-----END PUBLIC KEY-----".getBytes(StandardCharsets.UTF_8)
        ));
        createRequest.setApiStatus(1);
        Long configId = appManagementService.saveStoreConfig(createRequest).id();

        mockMvc.perform(get("/api/store-configs/{configId}", configId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeType").value("xiaomi"))
                .andExpect(jsonPath("$.data.publicKey").value("-----BEGIN PUBLIC KEY-----\ncreate-public-key\n-----END PUBLIC KEY-----"));

        MockMultipartFile updatePublicKey = new MockMultipartFile(
                "publicKeyFile",
                "xiaomi-update-public.pem",
                "application/octet-stream",
                "-----BEGIN PUBLIC KEY-----\nupdate-public-key\n-----END PUBLIC KEY-----".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/store-configs/{configId}", configId)
                        .file(updatePublicKey)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .param("storeType", "xiaomi")
                        .param("accountName", "xiaomi-account-updated")
                        .param("email", "xiaomi-updated@example.com")
                        .param("apiStatus", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountName").value("xiaomi-account-updated"))
                .andExpect(jsonPath("$.data.publicKey").value("-----BEGIN PUBLIC KEY-----\nupdate-public-key\n-----END PUBLIC KEY-----"));
    }

    @Test
    void shouldCreateAndUpdateXiaomiStoreConfigWithCerFile() throws Exception {
        StoreConfigRequest createRequest = new StoreConfigRequest();
        createRequest.setStoreType("xiaomi");
        createRequest.setAccountName("xiaomi-cer-account");
        createRequest.setEmail("xiaomi-cer@example.com");
        createRequest.setPublicKeyFile(new MockMultipartFile(
                "publicKeyFile",
                "xiaomi-create-public.cer",
                "application/pkix-cert",
                Base64.getDecoder().decode(TEST_XIAOMI_CERTIFICATE_BASE64)
        ));
        createRequest.setApiStatus(1);
        Long configId = appManagementService.saveStoreConfig(createRequest).id();

        mockMvc.perform(get("/api/store-configs/{configId}", configId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeType").value("xiaomi"))
                .andExpect(jsonPath("$.data.publicKey").value(TEST_XIAOMI_PUBLIC_KEY_PEM));

        MockMultipartFile updatePublicKey = new MockMultipartFile(
                "publicKeyFile",
                "xiaomi-update-public.cer",
                "application/pkix-cert",
                Base64.getDecoder().decode(TEST_XIAOMI_CERTIFICATE_BASE64)
        );

        mockMvc.perform(multipart("/api/store-configs/{configId}", configId)
                        .file(updatePublicKey)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .param("storeType", "xiaomi")
                        .param("accountName", "xiaomi-cer-account-updated")
                        .param("email", "xiaomi-cer-updated@example.com")
                        .param("apiStatus", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountName").value("xiaomi-cer-account-updated"))
                .andExpect(jsonPath("$.data.publicKey").value(TEST_XIAOMI_PUBLIC_KEY_PEM));
    }

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
                .andExpect(jsonPath("$.data.versions[0].packageUrl64").isEmpty())
                .andExpect(jsonPath("$.data.versions[0].packageAppUrl").isEmpty());

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
