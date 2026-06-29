package com.app.publishservice.service;

import com.app.publishservice.domain.enums.ReleaseStatus;
import org.springframework.util.StringUtils;

import java.util.Map;

final class StoreValueSupport {

    ReleaseStatus mapStatus(String value) {
        return switch (value.toLowerCase()) {
            case "pass", "approved", "online" -> ReleaseStatus.PASS;
            case "reject", "rejected", "failed" -> ReleaseStatus.REJECT;
            case "offline" -> ReleaseStatus.OFFLINE;
            default -> ReleaseStatus.AUDITING;
        };
    }

    String firstString(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    String normalizeTitle(String appName, String packageName) {
        String title = firstNonBlank(appName, packageName, "\u5e94\u7528\u53d1\u5e03");
        title = title.replaceAll("[\\r\\n\\t]", " ").trim();
        return title.length() <= 20 ? title : title.substring(0, 20);
    }

    String normalizeStageText(int minLength, int maxLength, String... candidates) {
        String text = firstNonBlank(candidates);
        text = text.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        if (!StringUtils.hasText(text)) {
            text = "\u5e94\u7528\u53d1\u5e03\u8bf4\u660e";
        }
        while (text.length() < minLength) {
            text = text + "\uff0c\u8bf7\u5173\u6ce8\u66f4\u65b0\u5185\u5bb9";
        }
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength);
        }
        return text;
    }

    String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    Object firstNonNull(Object... values) {
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
}
