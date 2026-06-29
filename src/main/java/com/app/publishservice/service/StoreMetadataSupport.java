package com.app.publishservice.service;

import com.app.publishservice.common.exception.StoreApiException;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class StoreMetadataSupport {

    private final PublishMetadataResolver publishMetadataResolver;
    private final ObjectMapper objectMapper;

    StoreMetadataSupport(PublishMetadataResolver publishMetadataResolver, ObjectMapper objectMapper) {
        this.publishMetadataResolver = publishMetadataResolver;
        this.objectMapper = objectMapper;
    }

    String writeJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            return String.valueOf(object);
        }
    }

    AbstractStorePlatformPublisher.ProjectMetadataContext resolveProjectMetadataContext(String packageLocation) {
        return new AbstractStorePlatformPublisher.ProjectMetadataContext(
                publishMetadataResolver.metadataPath(),
                publishMetadataResolver.metadata()
        );
    }

    Path resolveProjectAssetPath(Path metadataPath, Object assetLocation) {
        return publishMetadataResolver.resolveAssetPath(metadataPath, assetLocation);
    }

    List<Path> resolveProjectAssetPaths(
            Path metadataPath,
            List<String> assetLocations,
            String assetNotFoundMessagePrefix
    ) {
        return publishMetadataResolver.resolveAssetPaths(metadataPath, assetLocations, assetNotFoundMessagePrefix);
    }

    Object metadataLookup(Map<String, Object> metadata, String sectionKey, String key) {
        return publishMetadataResolver.metadataLookup(metadata, sectionKey, key);
    }

    List<String> firstList(Object... values) {
        if (values == null) {
            return List.of();
        }
        for (Object value : values) {
            if (value instanceof List<?> list && !list.isEmpty()) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    if (item != null && StringUtils.hasText(String.valueOf(item))) {
                        result.add(String.valueOf(item));
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return List.of();
    }

    Integer firstInteger(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            int parsed = intValue(value);
            if (parsed >= 0) {
                return parsed;
            }
        }
        return null;
    }

    Map<String, Object> readJson(String body) {
        if (!StringUtils.hasText(body)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new StoreApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Store API returned unexpected response" + buildResponseBodySuffix(body),
                    ex
            );
        }
    }

    Map<String, Object> readJsonIfPossible(String body) {
        if (!StringUtils.hasText(body)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String buildResponseBodySuffix(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "";
        }
        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 300) {
            normalized = normalized.substring(0, 300) + "...";
        }
        return ", body=" + normalized;
    }
}
