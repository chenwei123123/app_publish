package com.app.publishservice.service;

import com.app.publishservice.service.model.PackageMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageInspectorServiceTest {

    private final PackageInspectorService packageInspectorService = new PackageInspectorService(new ObjectMapper());

    /**
     * 测试Read 元数据 Archive场景。
     */
    @Test
    void shouldReadMetadataFromArchive() throws Exception {
        Path tempFile = Files.createTempFile("demo-1.2.3-build45-reinforced", ".apk");
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(tempFile), StandardCharsets.UTF_8)) {
            zipOutputStream.putNextEntry(new ZipEntry("app-publish-metadata.json"));
            zipOutputStream.write("""
                    {
                      "versionName": "1.2.3",
                      "versionCode": 45,
                      "reinforced": true
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }

        PackageMetadata metadata = packageInspectorService.inspect(tempFile);

        assertEquals("apk", metadata.packageType());
        assertEquals("1.2.3", metadata.versionName());
        assertEquals("45", metadata.versionCode());
        assertTrue(metadata.reinforced());
        assertEquals(64, metadata.checksum().length());
    }

    /**
     * 测试Read 字符串版本编码 Archive场景。
     */
    @Test
    void shouldReadStringVersionCodeFromArchive() throws Exception {
        Path tempFile = Files.createTempFile("demo-1.2.3-build-release-45", ".apk");
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(tempFile), StandardCharsets.UTF_8)) {
            zipOutputStream.putNextEntry(new ZipEntry("app-publish-metadata.json"));
            zipOutputStream.write("""
                    {
                      "versionName": "1.2.3",
                      "versionCode": "release-45",
                      "reinforced": false
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }

        PackageMetadata metadata = packageInspectorService.inspect(tempFile);

        assertEquals("1.2.3", metadata.versionName());
        assertEquals("release-45", metadata.versionCode());
    }
}
