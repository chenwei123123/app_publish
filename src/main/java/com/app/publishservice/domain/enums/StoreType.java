package com.app.publishservice.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import java.util.Locale;

public enum StoreType {
    华为应用市场("华为应用市场", "huawei", "华为应用市场"),
    小米应用市场("小米应用市场", "xiaomi", "小米应用市场"),
    OPPO软件商店("OPPO软件商店", "oppo", "OPPO软件商店"),
    VIVO应用商店("VIVO应用商店", "vivo", "VIVO应用商店"),
    应用宝("应用宝", "yingyongbao", "应用宝"),
    三星应用市场("三星应用市场", "sanxing", "三星应用市场"),
    荣耀应用市场("荣耀应用市场", "rongyao", "荣耀应用市场");

    @EnumValue
    private final String value;
    private final String code;
    private final String description;

    /**
     * 初始化商店类型。
     */
    StoreType(String value, String code, String description) {
        this.value = value;
        this.code = code;
        this.description = description;
    }

    /**
     * 获取值。
     */
    public String getValue() {
        return value;
    }

    /**
     * 获取编码。
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取Description。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 处理值相关逻辑。
     */
    public static StoreType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Store type value cannot be blank");
        }
        String normalized = value.trim();
        for (StoreType item : values()) {
            if (item.value.equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Unsupported store type value: " + value);
    }

    /**
     * 处理编码相关逻辑。
     */
    public static StoreType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Store type code cannot be blank");
        }
        String normalized = code.trim();
        for (StoreType item : values()) {
            if (item.code.equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Unsupported store type code: " + code);
    }


    /**
     * 处理Description Fuzzy相关逻辑。
     */
    public static StoreType fromDescriptionFuzzy(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Store type description cannot be blank");
        }
        String normalized = description.trim().toLowerCase(Locale.ROOT);
        for (StoreType item : values()) {
            if (item.description.equalsIgnoreCase(description.trim())) {
                return item;
            }
        }
        for (StoreType item : values()) {
            String itemDescription = item.description.toLowerCase(Locale.ROOT);
            if (itemDescription.contains(normalized) || normalized.contains(itemDescription)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Unsupported store type description: " + description);
    }

    /**
     * 处理值 Description Fuzzy相关逻辑。
     */
    public static String valueFromDescriptionFuzzy(String description) {
        return fromDescriptionFuzzy(description).getValue();
    }
}
