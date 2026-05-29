ALTER TABLE app_store_config
    MODIFY COLUMN store_type VARCHAR(20) NOT NULL COMMENT '渠道类型';

ALTER TABLE app_release_record
    MODIFY COLUMN store_type VARCHAR(20) NOT NULL COMMENT '目标渠道';
