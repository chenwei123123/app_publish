CREATE TABLE IF NOT EXISTS app_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '应用 ID',
    app_name VARCHAR(100) NOT NULL COMMENT '应用名称',
    package_name VARCHAR(128) NOT NULL COMMENT '包名或 Bundle ID',
    app_type TINYINT NOT NULL DEFAULT 1 COMMENT '1=安卓应用 2=iOS应用',
    app_description VARCHAR(512) DEFAULT NULL COMMENT '应用描述',
    copyright_no VARCHAR(100) DEFAULT NULL COMMENT '软件著作权登记号',
    icp_no VARCHAR(100) DEFAULT NULL COMMENT 'ICP备案号',
    app_record_no VARCHAR(100) DEFAULT NULL COMMENT '工信部 APP 备案号',
    privacy_url VARCHAR(255) DEFAULT NULL COMMENT '隐私政策地址',
    user_agreement_url VARCHAR(255) DEFAULT NULL COMMENT '用户协议地址',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_app_info_package_name (package_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用基础信息表';

CREATE TABLE IF NOT EXISTS app_store_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键 ID',
    store_type VARCHAR(20) NOT NULL COMMENT '渠道类型',
    account_name VARCHAR(255) DEFAULT NULL COMMENT '开发者账号名称',
    email VARCHAR(255) DEFAULT NULL COMMENT '登录邮箱',
    phone VARCHAR(255) DEFAULT NULL COMMENT '登录手机号',
    client_id VARCHAR(255) DEFAULT NULL COMMENT 'OAuth Client ID 或 API Key',
    client_secret VARCHAR(255) DEFAULT NULL COMMENT 'OAuth Client Secret 或 API Secret',
    mi_public_key TEXT DEFAULT NULL COMMENT '小米公钥',
    mi_private_key VARCHAR(255) DEFAULT NULL COMMENT '小米私钥',
    token VARCHAR(500) DEFAULT NULL COMMENT '固定 Token',
    ip_whitelist VARCHAR(500) DEFAULT NULL COMMENT 'IP 白名单',
    api_status TINYINT NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_store_config_store_type (store_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局渠道 API 配置表';

CREATE TABLE IF NOT EXISTS app_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '版本 ID',
    app_id BIGINT NOT NULL COMMENT '关联应用 ID',
    version_name VARCHAR(30) NOT NULL COMMENT '展示版本号',
    version_code INT NOT NULL DEFAULT 0 COMMENT '安卓 versionCode 或 iOS build',
    package_url VARCHAR(255) DEFAULT NULL COMMENT '安装包文件路径',
    update_log TEXT COMMENT '更新说明',
    is_reinforce TINYINT NOT NULL DEFAULT 0 COMMENT '0=否 1=是',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_app_version_unique (app_id, version_name, version_code),
    KEY idx_app_version_app (app_id),
    CONSTRAINT fk_app_version_app FOREIGN KEY (app_id) REFERENCES app_info(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用版本表';

CREATE TABLE IF NOT EXISTS app_release_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '发布记录 ID',
    app_id BIGINT NOT NULL COMMENT '应用 ID',
    version_id BIGINT NOT NULL COMMENT '版本 ID',
    store_type VARCHAR(20) NOT NULL COMMENT '目标渠道',
    release_mode VARCHAR(20) NOT NULL DEFAULT 'api' COMMENT '手动或 API',
    release_status VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT '发布状态',
    store_release_id VARCHAR(100) DEFAULT NULL COMMENT '渠道侧发布单号',
    reject_reason TEXT COMMENT '驳回原因',
    api_request_log TEXT COMMENT 'API 请求日志',
    api_response_log TEXT COMMENT 'API 响应日志',
    release_time DATETIME DEFAULT NULL COMMENT '提交时间',
    finish_time DATETIME DEFAULT NULL COMMENT '完成时间',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    KEY idx_release_record_app (app_id),
    KEY idx_release_record_version (version_id),
    KEY idx_release_status_store (release_status, store_type),
    CONSTRAINT fk_release_record_app FOREIGN KEY (app_id) REFERENCES app_info(id),
    CONSTRAINT fk_release_record_version FOREIGN KEY (version_id) REFERENCES app_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布记录表';

CREATE TABLE IF NOT EXISTS app_api_token_cache (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键 ID',
    store_config_id BIGINT NOT NULL COMMENT '关联渠道配置 ID',
    token_type VARCHAR(20) NOT NULL COMMENT 'Token 类型',
    token_value TEXT COMMENT 'Token 值',
    expire_time DATETIME DEFAULT NULL COMMENT '过期时间',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_token_cache_store_type (store_config_id, token_type),
    CONSTRAINT fk_token_cache_store_config FOREIGN KEY (store_config_id) REFERENCES app_store_config(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道 Token 缓存表';

CREATE TABLE IF NOT EXISTS app_release_task_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志 ID',
    release_record_id BIGINT NOT NULL COMMENT '关联发布记录 ID',
    action VARCHAR(50) NOT NULL COMMENT '操作名称',
    status_before VARCHAR(20) DEFAULT NULL COMMENT '变更前状态',
    status_after VARCHAR(20) DEFAULT NULL COMMENT '变更后状态',
    message VARCHAR(500) DEFAULT NULL COMMENT '摘要消息',
    payload TEXT COMMENT '详细载荷',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_release_task_log_record (release_record_id),
    CONSTRAINT fk_release_task_log_record FOREIGN KEY (release_record_id) REFERENCES app_release_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布任务日志表';
