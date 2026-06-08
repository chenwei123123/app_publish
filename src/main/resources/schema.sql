CREATE TABLE IF NOT EXISTS app_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '应用ID',
    app_name VARCHAR(100) NOT NULL COMMENT '应用名称',
    package_name VARCHAR(128) NOT NULL COMMENT '包名或Bundle ID',
    app_type TINYINT NOT NULL DEFAULT 1 COMMENT '应用类型，1=Android 2=iOS',
    app_description VARCHAR(512) DEFAULT NULL COMMENT '应用描述',
    copyright_no VARCHAR(100) DEFAULT NULL COMMENT '软件著作权登记号',
    icp_no VARCHAR(100) DEFAULT NULL COMMENT 'ICP备案号',
    app_record_no VARCHAR(100) DEFAULT NULL COMMENT '应用备案号',
    privacy_url VARCHAR(255) DEFAULT NULL COMMENT '隐私政策地址',
    user_agreement_url VARCHAR(255) DEFAULT NULL COMMENT '用户协议地址',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态，0=禁用 1=启用',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_app_info_package_name (package_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用基础信息表';

CREATE TABLE IF NOT EXISTS app_store_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    store_type VARCHAR(20) NOT NULL COMMENT '应用市场类型',
    account_name VARCHAR(255) DEFAULT NULL COMMENT '账号名称',
    email VARCHAR(255) DEFAULT NULL COMMENT '邮箱',
    phone VARCHAR(255) DEFAULT NULL COMMENT '手机号',
    client_id VARCHAR(255) DEFAULT NULL COMMENT '客户端ID或API Key',
    client_secret VARCHAR(255) DEFAULT NULL COMMENT '客户端密钥或API Secret',
    mi_public_key TEXT DEFAULT NULL COMMENT '小米公钥',
    mi_private_key VARCHAR(255) DEFAULT NULL COMMENT '小米私钥',
    token VARCHAR(500) DEFAULT NULL COMMENT '静态Token',
    ip_whitelist VARCHAR(500) DEFAULT NULL COMMENT 'IP白名单',
    api_status TINYINT NOT NULL DEFAULT 1 COMMENT '接口状态，0=禁用 1=启用',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_store_config_store_type (store_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用市场配置表';

CREATE TABLE IF NOT EXISTS app_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '版本ID',
    app_id BIGINT NOT NULL COMMENT '所属应用ID',
    version_name VARCHAR(30) NOT NULL COMMENT '版本名称',
    version_code INT NOT NULL DEFAULT 0 COMMENT '版本编码',
    package_url_low VARCHAR(255) DEFAULT NULL COMMENT '32位安装包路径',
    package_url_high VARCHAR(255) DEFAULT NULL COMMENT '64位安装包路径',
    build_code VARCHAR(64) DEFAULT NULL COMMENT '构建号',
    update_log TEXT COMMENT '更新说明',
    is_reinforce TINYINT NOT NULL DEFAULT 0 COMMENT '是否加固，0=否 1=是',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_app_version_unique (app_id, version_name, version_code),
    KEY idx_app_version_app (app_id),
    CONSTRAINT fk_app_version_app FOREIGN KEY (app_id) REFERENCES app_info(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用版本表';

ALTER TABLE app_version ADD COLUMN IF NOT EXISTS package_url_low VARCHAR(255) DEFAULT NULL COMMENT '32位安装包路径';
ALTER TABLE app_version ADD COLUMN IF NOT EXISTS package_url_high VARCHAR(255) DEFAULT NULL COMMENT '64位安装包路径';
ALTER TABLE app_version ADD COLUMN IF NOT EXISTS build_code VARCHAR(64) DEFAULT NULL COMMENT '构建号';
ALTER TABLE app_version DROP COLUMN IF EXISTS package_url;

CREATE TABLE IF NOT EXISTS app_release_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '发布记录ID',
    app_id BIGINT NOT NULL COMMENT '应用ID',
    version_id BIGINT NOT NULL COMMENT '版本ID',
    store_type VARCHAR(20) NOT NULL COMMENT '应用市场类型',
    release_mode VARCHAR(20) NOT NULL DEFAULT 'api' COMMENT '发布方式',
    release_type BIGINT NOT NULL DEFAULT 1 COMMENT '发布类型，1=全量 2=分阶段',
    gray_percent BIGINT DEFAULT NULL COMMENT '灰度比例',
    gray_start_time DATETIME DEFAULT NULL COMMENT '灰度开始时间',
    gray_end_time DATETIME DEFAULT NULL COMMENT '灰度结束时间',
    auto_full BIGINT NOT NULL DEFAULT 1 COMMENT '是否自动转全量',
    release_status VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT '发布状态',
    store_release_id VARCHAR(100) DEFAULT NULL COMMENT '市场侧发布单号',
    reject_reason TEXT COMMENT '驳回原因',
    api_request_log TEXT COMMENT '接口请求日志',
    api_response_log TEXT COMMENT '接口响应日志',
    release_time DATETIME DEFAULT NULL COMMENT '提交时间',
    finish_time DATETIME DEFAULT NULL COMMENT '完成时间',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    KEY idx_release_record_app (app_id),
    KEY idx_release_record_version (version_id),
    KEY idx_release_status_store (release_status, store_type),
    CONSTRAINT fk_release_record_app FOREIGN KEY (app_id) REFERENCES app_info(id),
    CONSTRAINT fk_release_record_version FOREIGN KEY (version_id) REFERENCES app_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用发布记录表';

CREATE TABLE IF NOT EXISTS app_api_token_cache (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    store_config_id BIGINT NOT NULL COMMENT '关联市场配置ID',
    token_type VARCHAR(20) NOT NULL COMMENT 'Token类型',
    token_value TEXT COMMENT 'Token值',
    expire_time DATETIME DEFAULT NULL COMMENT '过期时间',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_token_cache_store_type (store_config_id, token_type),
    CONSTRAINT fk_token_cache_store_config FOREIGN KEY (store_config_id) REFERENCES app_store_config(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token缓存表';

CREATE TABLE IF NOT EXISTS app_release_task_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '任务日志ID',
    release_record_id BIGINT NOT NULL COMMENT '关联发布记录ID',
    action VARCHAR(50) NOT NULL COMMENT '操作动作',
    status_before VARCHAR(20) DEFAULT NULL COMMENT '变更前状态',
    status_after VARCHAR(20) DEFAULT NULL COMMENT '变更后状态',
    message VARCHAR(500) DEFAULT NULL COMMENT '日志摘要',
    payload TEXT COMMENT '详细载荷',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_release_task_log_record (release_record_id),
    CONSTRAINT fk_release_task_log_record FOREIGN KEY (release_record_id) REFERENCES app_release_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布任务日志表';

CREATE TABLE IF NOT EXISTS app_store_request_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '市场请求日志ID',
    release_record_id BIGINT DEFAULT NULL COMMENT '关联发布记录ID',
    store_config_id BIGINT NOT NULL COMMENT '关联市场配置ID',
    store_type VARCHAR(20) NOT NULL COMMENT '应用市场类型',
    action VARCHAR(100) NOT NULL COMMENT '请求动作',
    request_order BIGINT DEFAULT NULL COMMENT '同一次发布内的请求顺序',
    request_method VARCHAR(10) NOT NULL COMMENT '请求方法',
    request_url VARCHAR(500) NOT NULL COMMENT '请求地址',
    request_params TEXT COMMENT '请求参数',
    request_body LONGTEXT COMMENT '请求体',
    response_status_code INT DEFAULT NULL COMMENT '响应状态码',
    response_body LONGTEXT COMMENT '响应内容',
    request_status VARCHAR(20) NOT NULL COMMENT '请求状态',
    error_message VARCHAR(1000) DEFAULT NULL COMMENT '错误信息',
    duration_ms BIGINT DEFAULT NULL COMMENT '请求耗时（毫秒）',
    create_user VARCHAR(255) DEFAULT NULL COMMENT '创建人',
    update_user VARCHAR(255) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_store_request_log_release_order (release_record_id, request_order),
    KEY idx_store_request_log_store_type_time (store_type, create_time),
    CONSTRAINT fk_store_request_log_release_record FOREIGN KEY (release_record_id) REFERENCES app_release_record(id),
    CONSTRAINT fk_store_request_log_store_config FOREIGN KEY (store_config_id) REFERENCES app_store_config(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用市场请求日志表';
