package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PublishMetadataResolver {

    private final AppProperties appProperties;

    /**
     * 初始化PublishMetadataResolver。
     */
    PublishMetadataResolver(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 处理元数据路径相关逻辑。
     */
    Path metadataPath() {
        return metadataBaseDir().resolve("application.yml").normalize();
    }

    /**
     * 处理元数据相关逻辑。
     */
    Map<String, Object> metadata() {
        AppProperties.PublishMetadataProperties metadataProperties = appProperties.getPublishMetadata();
        if (metadataProperties == null || metadataProperties.getValues() == null) {
            return Map.of();
        }
        return metadataProperties.getValues();
    }

    /**
     * 解析Asset 路径。
     */
    Path resolveAssetPath(Path metadataPath, Object assetLocation) {
        if (assetLocation == null) {
            return null;
        }
        String location = String.valueOf(assetLocation).trim();
        if (!StringUtils.hasText(location)) {
            return null;
        }

        Path directPath = toPath(location);
        if (directPath == null) {
            return null;
        }
        if (directPath.isAbsolute()) {
            return Files.exists(directPath) && Files.isRegularFile(directPath) ? directPath : null;
        }

        Path basePath = metadataPath == null ? metadataBaseDir() : metadataPath.toAbsolutePath().normalize().getParent();
        if (basePath == null) {
            return null;
        }
        Path resolved = basePath.resolve(directPath).normalize();
        return Files.exists(resolved) && Files.isRegularFile(resolved) ? resolved : null;
    }

    /**
     * 解析Asset 路径。
     */
    List<Path> resolveAssetPaths(Path metadataPath, List<String> assetLocations, String assetNotFoundMessagePrefix) {
        if (assetLocations == null || assetLocations.isEmpty()) {
            return List.of();
        }
        List<Path> resolved = new ArrayList<>();
        for (String assetLocation : assetLocations) {
            Path path = resolveAssetPath(metadataPath, assetLocation);
            if (path == null) {
                throw new IllegalStateException(assetNotFoundMessagePrefix + assetLocation);
            }
            resolved.add(path);
        }
        return resolved;
    }

    /**
     * 处理元数据 Lookup相关逻辑。
     */
    Object metadataLookup(Map<String, Object> metadata, String sectionKey, String key) {
        if (metadata == null || !StringUtils.hasText(key)) {
            return null;
        }
        if (StringUtils.hasText(sectionKey)) {
            Map<String, Object> section = asMap(metadata.get(sectionKey));
            if (!section.isEmpty()) {
                Object value = firstNonNull(section.get(key), section.get(toSnakeCase(key)));
                if (value != null) {
                    return value;
                }
            }
        }
        return firstNonNull(metadata.get(key), metadata.get(toSnakeCase(key)));
    }

    /**
     * 处理元数据 Base Dir相关逻辑。
     */
    private Path metadataBaseDir() {
        AppProperties.PublishMetadataProperties metadataProperties = appProperties.getPublishMetadata();
        String configuredBaseDir = metadataProperties == null ? null : metadataProperties.getBaseDir();
        if (!StringUtils.hasText(configuredBaseDir)) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        Path configuredPath = toPath(configuredBaseDir.trim());
        if (configuredPath == null) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        return configuredPath.toAbsolutePath().normalize();
    }

    /**
     * 处理as 映射相关逻辑。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /**
     * 获取首个Non Null。
     */
    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 处理Snake Case相关逻辑。
     */
    private String toSnakeCase(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /**
     * 处理路径相关逻辑。
     */
    private Path toPath(String location) {
        try {
            return Path.of(location);
        } catch (InvalidPathException ex) {
            return null;
        }
    }
}
