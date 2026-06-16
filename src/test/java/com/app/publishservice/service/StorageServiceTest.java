package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageServiceTest {

    /**
     * 测试Allocate 下载路径 Environment 版本 Build场景。
     */
    @Test
    void shouldAllocateDownloadPathByEnvironmentVersionAndBuild(@TempDir Path tempDir) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setStorageRoot(tempDir.resolve("storage").toString());
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});

        StorageService storageService = new StorageService(properties, environment);

        Path target = storageService.allocateDownloadPath("1.12.1", "b1843", "cms_yht_32.apk");

        assertEquals(
                tempDir.resolve("storage").resolve("test").resolve("1.12.1+b1843").resolve("cms_yht_32.apk").toAbsolutePath().normalize(),
                target.toAbsolutePath().normalize()
        );
    }
}
