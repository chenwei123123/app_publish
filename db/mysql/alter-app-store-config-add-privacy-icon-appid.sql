ALTER TABLE app_store_config
    ADD COLUMN privacy_url VARCHAR(255) DEFAULT NULL COMMENT '小米隐私政策地址' AFTER ip_whitelist,
    ADD COLUMN icon LONGTEXT DEFAULT NULL COMMENT '小米图标 Base64' AFTER privacy_url,
    ADD COLUMN app_id VARCHAR(100) DEFAULT NULL COMMENT '应用宝 appId / 三星 contentId' AFTER icon;
