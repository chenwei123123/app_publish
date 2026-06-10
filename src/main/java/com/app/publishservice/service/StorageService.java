package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.UUID;

@Service
public class StorageService {

    private final Path rootPath;
    private final Environment environment;

    public StorageService(AppProperties properties, Environment environment) throws IOException {
        this.rootPath = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
        this.environment = environment;
        Files.createDirectories(rootPath);
    }

    public Path allocatePath(String originalFilename) throws IOException {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.'))
                : "";
        Path targetDir = rootPath.resolve(day);
        Files.createDirectories(targetDir);
        return targetDir.resolve(UUID.randomUUID() + extension);
    }

    public Path allocateDownloadPath(String versionCode, String buildCode, String originalFilename) throws IOException {
        String fileName = normalizeFileName(originalFilename);
        Path targetDir = rootPath
                .resolve(currentEnvironmentName())
                .resolve(buildDownloadDirectoryName(versionCode, buildCode));
        Files.createDirectories(targetDir);
        return targetDir.resolve(fileName);
    }

    private String currentEnvironmentName() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length > 0) {
            return sanitizeSegment(activeProfiles[0]);
        }
        String[] defaultProfiles = environment.getDefaultProfiles();
        if (defaultProfiles.length > 0) {
            return sanitizeSegment(defaultProfiles[0]);
        }
        return "default";
    }

    private String normalizeFileName(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return UUID.randomUUID().toString();
        }
        String normalized = originalFilename.replace('\\', '/');
        int separatorIndex = normalized.lastIndexOf('/');
        String fileName = separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
        return StringUtils.hasText(fileName) ? fileName : UUID.randomUUID().toString();
    }

    private String buildDownloadDirectoryName(String versionCode, String buildCode) {
        String versionSegment = StringUtils.hasText(versionCode) ? versionCode.trim() : "unknown-version";
        String buildSegment = StringUtils.hasText(buildCode) ? buildCode.trim() : "unknown-build";
        return sanitizeSegment(versionSegment) + "+" + sanitizeSegment(buildSegment);
    }

    private String sanitizeSegment(String value) {
        String sanitized = Arrays.stream(value.trim().split("[^a-zA-Z0-9._-]+"))
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "_" + right)
                .orElse("default");
        return sanitized;
    }
}
