package com.app.publishservice.api;

import com.app.publishservice.handler.GlobalExceptionHandler;
import com.app.publishservice.service.ReleaseOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReleaseControllerTest {

    private static final String APK32_DOWNLOAD_FAILED_MESSAGE =
            "\u4ECEhttps://download.example.com/demo-32.apk\u4E0B\u8F7D32\u4F4Dapk\u5931\u8D25\uFF0C\u8BF7\u6838\u5BF9\u7248\u672C\u53F7\u548C\u6784\u5EFA\u53F7\u662F\u5426\u6B63\u786E";

    /**
     * 测试Return Download32 Failure 消息 Directly场景。
     */
    @Test
    void shouldReturnDownload32FailureMessageDirectly() throws Exception {
        ReleaseOrchestrationService releaseOrchestrationService = mock(ReleaseOrchestrationService.class);
        when(releaseOrchestrationService.submit(any()))
                .thenThrow(new IllegalArgumentException(APK32_DOWNLOAD_FAILED_MESSAGE));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ReleaseController(releaseOrchestrationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/releases/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "versionId": 9,
                                  "releaseMode": "api",
                                  "storeTypes": ["huawei"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(APK32_DOWNLOAD_FAILED_MESSAGE));
    }
}
