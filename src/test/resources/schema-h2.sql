CREATE TABLE IF NOT EXISTS app_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name VARCHAR(100) NOT NULL,
    package_name VARCHAR(128) NOT NULL,
    app_type TINYINT NOT NULL DEFAULT 1,
    app_description VARCHAR(512),
    copyright_no VARCHAR(100),
    icp_no VARCHAR(100),
    app_record_no VARCHAR(100),
    privacy_url VARCHAR(255),
    user_agreement_url VARCHAR(255),
    status TINYINT NOT NULL DEFAULT 1,
    create_user VARCHAR(255),
    update_user VARCHAR(255),
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    CONSTRAINT uk_app_info_package_name UNIQUE (package_name)
);

CREATE TABLE IF NOT EXISTS app_store_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    store_type VARCHAR(20) NOT NULL,
    account_name VARCHAR(255),
    email VARCHAR(255),
    phone VARCHAR(255),
    client_id VARCHAR(255),
    client_secret VARCHAR(255),
    public_key CLOB,
    private_key VARCHAR(255),
    token VARCHAR(500),
    ip_whitelist VARCHAR(500),
    privacy_url VARCHAR(255),
    icon CLOB,
    app_id VARCHAR(100),
    api_status TINYINT NOT NULL DEFAULT 1,
    create_user VARCHAR(255),
    update_user VARCHAR(255),
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    CONSTRAINT uk_store_config_store_type UNIQUE (store_type)
);

CREATE TABLE IF NOT EXISTS app_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_id BIGINT NOT NULL,
    version_name VARCHAR(30) NOT NULL,
    version_code VARCHAR(64) NOT NULL,
    package_url_32 VARCHAR(255),
    package_url_64 VARCHAR(255),
    build_code VARCHAR(64),
    update_log CLOB,
    is_reinforce TINYINT NOT NULL DEFAULT 0,
    create_user VARCHAR(255),
    update_user VARCHAR(255),
    create_time TIMESTAMP NOT NULL,
    CONSTRAINT uk_app_version_unique UNIQUE (app_id, version_name, version_code),
    CONSTRAINT fk_app_version_app FOREIGN KEY (app_id) REFERENCES app_info (id)
);
CREATE INDEX IF NOT EXISTS idx_app_version_app ON app_version (app_id);

CREATE TABLE IF NOT EXISTS app_release_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    store_type VARCHAR(20) NOT NULL,
    release_mode VARCHAR(20) NOT NULL DEFAULT 'api',
    release_type BIGINT NOT NULL DEFAULT 1,
    gray_percent BIGINT,
    gray_start_time TIMESTAMP,
    gray_end_time TIMESTAMP,
    auto_full BIGINT NOT NULL DEFAULT 1,
    release_status VARCHAR(20) NOT NULL DEFAULT 'draft',
    store_release_id VARCHAR(100),
    reject_reason CLOB,
    api_request_log CLOB,
    api_response_log CLOB,
    release_time TIMESTAMP,
    finish_time TIMESTAMP,
    create_user VARCHAR(255),
    update_user VARCHAR(255),
    CONSTRAINT fk_release_record_app FOREIGN KEY (app_id) REFERENCES app_info (id),
    CONSTRAINT fk_release_record_version FOREIGN KEY (version_id) REFERENCES app_version (id)
);
CREATE INDEX IF NOT EXISTS idx_release_record_app ON app_release_record (app_id);
CREATE INDEX IF NOT EXISTS idx_release_record_version ON app_release_record (version_id);
CREATE INDEX IF NOT EXISTS idx_release_status_store ON app_release_record (release_status, store_type);

CREATE TABLE IF NOT EXISTS app_api_token_cache (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    store_config_id BIGINT NOT NULL,
    token_type VARCHAR(20) NOT NULL,
    token_value CLOB,
    expire_time TIMESTAMP,
    create_user VARCHAR(255),
    update_user VARCHAR(255),
    update_time TIMESTAMP NOT NULL,
    CONSTRAINT uk_token_cache_store_type UNIQUE (store_config_id, token_type),
    CONSTRAINT fk_token_cache_store_config FOREIGN KEY (store_config_id) REFERENCES app_store_config (id)
);

CREATE TABLE IF NOT EXISTS app_store_request_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    release_record_id BIGINT,
    store_config_id BIGINT NOT NULL,
    store_type VARCHAR(20) NOT NULL,
    action VARCHAR(100) NOT NULL,
    request_order BIGINT,
    request_method VARCHAR(10) NOT NULL,
    request_url VARCHAR(500) NOT NULL,
    request_params CLOB,
    request_body CLOB,
    response_status_code INT,
    response_body CLOB,
    request_status VARCHAR(20) NOT NULL,
    error_message VARCHAR(1000),
    duration_ms BIGINT,
    create_user VARCHAR(255),
    update_user VARCHAR(255),
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    CONSTRAINT fk_store_request_log_release_record FOREIGN KEY (release_record_id) REFERENCES app_release_record (id),
    CONSTRAINT fk_store_request_log_store_config FOREIGN KEY (store_config_id) REFERENCES app_store_config (id)
);
CREATE INDEX IF NOT EXISTS idx_store_request_log_release_order ON app_store_request_log (release_record_id, request_order);
CREATE INDEX IF NOT EXISTS idx_store_request_log_store_type_time ON app_store_request_log (store_type, create_time);
