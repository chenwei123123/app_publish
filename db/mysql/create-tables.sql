CREATE TABLE IF NOT EXISTS app_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'App ID',
    app_name VARCHAR(100) NOT NULL COMMENT 'App name',
    package_name VARCHAR(128) NOT NULL COMMENT 'Package name or bundle ID',
    app_type TINYINT NOT NULL DEFAULT 1 COMMENT '1=Android 2=iOS',
    app_description VARCHAR(512) DEFAULT NULL COMMENT 'App description',
    copyright_no VARCHAR(100) DEFAULT NULL COMMENT 'Copyright number',
    icp_no VARCHAR(100) DEFAULT NULL COMMENT 'ICP number',
    app_record_no VARCHAR(100) DEFAULT NULL COMMENT 'App record number',
    privacy_url VARCHAR(255) DEFAULT NULL COMMENT 'Privacy policy URL',
    user_agreement_url VARCHAR(255) DEFAULT NULL COMMENT 'User agreement URL',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '0=disabled 1=enabled',
    create_user VARCHAR(255) DEFAULT NULL COMMENT 'Create user',
    update_user VARCHAR(255) DEFAULT NULL COMMENT 'Update user',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    UNIQUE KEY uk_app_info_package_name (package_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='App info table';

CREATE TABLE IF NOT EXISTS app_store_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
    store_type VARCHAR(20) NOT NULL COMMENT 'Store type',
    account_name VARCHAR(255) DEFAULT NULL COMMENT 'Account name',
    email VARCHAR(255) DEFAULT NULL COMMENT 'Email',
    phone VARCHAR(255) DEFAULT NULL COMMENT 'Phone',
    client_id VARCHAR(255) DEFAULT NULL COMMENT 'Client ID or API key',
    client_secret VARCHAR(255) DEFAULT NULL COMMENT 'Client secret or API secret',
    mi_public_key TEXT DEFAULT NULL COMMENT 'Xiaomi public key',
    mi_private_key VARCHAR(255) DEFAULT NULL COMMENT 'Xiaomi private key',
    token VARCHAR(500) DEFAULT NULL COMMENT 'Static token',
    ip_whitelist VARCHAR(500) DEFAULT NULL COMMENT 'IP whitelist',
    api_status TINYINT NOT NULL DEFAULT 1 COMMENT '0=disabled 1=enabled',
    create_user VARCHAR(255) DEFAULT NULL COMMENT 'Create user',
    update_user VARCHAR(255) DEFAULT NULL COMMENT 'Update user',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    UNIQUE KEY uk_store_config_store_type (store_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Store config table';

CREATE TABLE IF NOT EXISTS app_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Version ID',
    app_id BIGINT NOT NULL COMMENT 'Related app ID',
    version_name VARCHAR(30) NOT NULL COMMENT 'Version name',
    version_code INT NOT NULL DEFAULT 0 COMMENT 'Version code',
    package_url_low VARCHAR(255) DEFAULT NULL COMMENT '32-bit package path',
    package_url_high VARCHAR(255) DEFAULT NULL COMMENT '64-bit package path',
    build_code VARCHAR(64) DEFAULT NULL COMMENT 'Build code',
    update_log TEXT COMMENT 'Update log',
    is_reinforce TINYINT NOT NULL DEFAULT 0 COMMENT '0=no 1=yes',
    create_user VARCHAR(255) DEFAULT NULL COMMENT 'Create user',
    update_user VARCHAR(255) DEFAULT NULL COMMENT 'Update user',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    UNIQUE KEY uk_app_version_unique (app_id, version_name, version_code),
    KEY idx_app_version_app (app_id),
    CONSTRAINT fk_app_version_app FOREIGN KEY (app_id) REFERENCES app_info(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='App version table';

CREATE TABLE IF NOT EXISTS app_release_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Release record ID',
    app_id BIGINT NOT NULL COMMENT 'App ID',
    version_id BIGINT NOT NULL COMMENT 'Version ID',
    store_type VARCHAR(20) NOT NULL COMMENT 'Store type',
    release_mode VARCHAR(20) NOT NULL DEFAULT 'api' COMMENT 'Release mode',
    release_type BIGINT NOT NULL DEFAULT 1 COMMENT '1=full 2=staged',
    gray_percent BIGINT DEFAULT NULL COMMENT 'Gray percent',
    gray_start_time DATETIME DEFAULT NULL COMMENT 'Gray start time',
    gray_end_time DATETIME DEFAULT NULL COMMENT 'Gray end time',
    auto_full BIGINT NOT NULL DEFAULT 1 COMMENT 'Auto full release',
    release_status VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT 'Release status',
    store_release_id VARCHAR(100) DEFAULT NULL COMMENT 'Store release ID',
    reject_reason TEXT COMMENT 'Reject reason',
    api_request_log TEXT COMMENT 'API request summary',
    api_response_log TEXT COMMENT 'API response summary',
    release_time DATETIME DEFAULT NULL COMMENT 'Release time',
    finish_time DATETIME DEFAULT NULL COMMENT 'Finish time',
    create_user VARCHAR(255) DEFAULT NULL COMMENT 'Create user',
    update_user VARCHAR(255) DEFAULT NULL COMMENT 'Update user',
    KEY idx_release_record_app (app_id),
    KEY idx_release_record_version (version_id),
    KEY idx_release_status_store (release_status, store_type),
    CONSTRAINT fk_release_record_app FOREIGN KEY (app_id) REFERENCES app_info(id),
    CONSTRAINT fk_release_record_version FOREIGN KEY (version_id) REFERENCES app_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Release record table';

CREATE TABLE IF NOT EXISTS app_api_token_cache (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
    store_config_id BIGINT NOT NULL COMMENT 'Related store config ID',
    token_type VARCHAR(20) NOT NULL COMMENT 'Token type',
    token_value TEXT COMMENT 'Token value',
    expire_time DATETIME DEFAULT NULL COMMENT 'Expire time',
    create_user VARCHAR(255) DEFAULT NULL COMMENT 'Create user',
    update_user VARCHAR(255) DEFAULT NULL COMMENT 'Update user',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    UNIQUE KEY uk_token_cache_store_type (store_config_id, token_type),
    CONSTRAINT fk_token_cache_store_config FOREIGN KEY (store_config_id) REFERENCES app_store_config(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token cache table';

CREATE TABLE IF NOT EXISTS app_release_task_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Log ID',
    release_record_id BIGINT NOT NULL COMMENT 'Related release record ID',
    action VARCHAR(50) NOT NULL COMMENT 'Action',
    status_before VARCHAR(20) DEFAULT NULL COMMENT 'Status before',
    status_after VARCHAR(20) DEFAULT NULL COMMENT 'Status after',
    message VARCHAR(500) DEFAULT NULL COMMENT 'Message',
    payload TEXT COMMENT 'Payload',
    create_user VARCHAR(255) DEFAULT NULL COMMENT 'Create user',
    update_user VARCHAR(255) DEFAULT NULL COMMENT 'Update user',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    KEY idx_release_task_log_record (release_record_id),
    CONSTRAINT fk_release_task_log_record FOREIGN KEY (release_record_id) REFERENCES app_release_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Release task log table';

CREATE TABLE IF NOT EXISTS app_store_request_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Store request log ID',
    release_record_id BIGINT DEFAULT NULL COMMENT 'Related release record ID',
    store_config_id BIGINT NOT NULL COMMENT 'Related store config ID',
    store_type VARCHAR(20) NOT NULL COMMENT 'Store type',
    action VARCHAR(100) NOT NULL COMMENT 'Request action',
    request_order BIGINT DEFAULT NULL COMMENT 'Request order within the release record',
    request_method VARCHAR(10) NOT NULL COMMENT 'HTTP method',
    request_url VARCHAR(500) NOT NULL COMMENT 'Request URL',
    request_params TEXT COMMENT 'Request params',
    request_body LONGTEXT COMMENT 'Request body',
    response_status_code INT DEFAULT NULL COMMENT 'HTTP status code',
    response_body LONGTEXT COMMENT 'Response body',
    request_status VARCHAR(20) NOT NULL COMMENT 'Request status',
    error_message VARCHAR(1000) DEFAULT NULL COMMENT 'Error message',
    duration_ms BIGINT DEFAULT NULL COMMENT 'Request duration in milliseconds',
    create_user VARCHAR(255) DEFAULT NULL COMMENT 'Create user',
    update_user VARCHAR(255) DEFAULT NULL COMMENT 'Update user',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    KEY idx_store_request_log_release_order (release_record_id, request_order),
    KEY idx_store_request_log_store_type_time (store_type, create_time),
    CONSTRAINT fk_store_request_log_release_record FOREIGN KEY (release_record_id) REFERENCES app_release_record(id),
    CONSTRAINT fk_store_request_log_store_config FOREIGN KEY (store_config_id) REFERENCES app_store_config(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Store request log table';
