-- ============================================================================
-- 增量迁移：结构化原始记录与版本留痕
--
-- 适用：已经初始化过 V1/V2、不希望 docker compose down -v 重建数据的库。
-- 全新环境无需执行——最新 schema.sql 已包含全部表和演示数据。
--
-- 特性：
--   1. 幂等，可重复执行；
--   2. 只补结构，不删除、不覆盖旧 measured_data；
--   3. 旧委托默认 LEGACY_TEXT，已核验/已发放记录仍按原状态和原文只读展示；
--   4. 尚未进入核验的旧委托由应用接口在用户确认后转成第一版结构化草稿。
-- ============================================================================
SET NAMES utf8mb4;

-- 1. 委托表补充记录模式和两个固定版本指针
SET @ddl := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE lab_calibration_order ADD COLUMN record_mode VARCHAR(16) NOT NULL DEFAULT ''LEGACY_TEXT'' COMMENT ''LEGACY_TEXT/STRUCTURED/LEGACY_READONLY'' AFTER measured_data',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lab_calibration_order' AND COLUMN_NAME = 'record_mode'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE lab_calibration_order ADD COLUMN current_record_version_id BIGINT NULL COMMENT ''当前已确认版本'' AFTER record_mode',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lab_calibration_order' AND COLUMN_NAME = 'current_record_version_id'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE lab_calibration_order ADD COLUMN cert_record_version_id BIGINT NULL COMMENT ''证书固定引用版本'' AFTER current_record_version_id',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lab_calibration_order' AND COLUMN_NAME = 'cert_record_version_id'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_order_current_record ON lab_calibration_order(current_record_version_id)',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lab_calibration_order' AND INDEX_NAME = 'idx_order_current_record'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_order_cert_record ON lab_calibration_order(cert_record_version_id)',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lab_calibration_order' AND INDEX_NAME = 'idx_order_cert_record'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 版本表
CREATE TABLE IF NOT EXISTS lab_record_version (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    order_id          BIGINT       NOT NULL COMMENT '委托单 id',
    version_no        INT          NOT NULL COMMENT '版本号，从 1 开始',
    status            VARCHAR(16)  NOT NULL COMMENT 'DRAFT/SUBMITTED/RETURNED/CONFIRMED/SUPERSEDED/WITHDRAWN',
    source            VARCHAR(20)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW/MIGRATED/COPY_REJECTED/COPY_WITHDRAWN/COPY_REVISION',
    source_version_id BIGINT                COMMENT '复制来源版本',
    legacy_source_text VARCHAR(500)         COMMENT '旧文本迁移原文快照',
    verifier_name     VARCHAR(32)           COMMENT '提交时固定的检定员',
    checker_name      VARCHAR(32)           COMMENT '处理本版本的核验员指派',
    conclusion        VARCHAR(16)           COMMENT '本版本结论',
    record_remark     VARCHAR(200)          COMMENT '整份原始记录备注',
    submitted_at      DATETIME              COMMENT '提交复核时间',
    reviewed_at       DATETIME              COMMENT '核验时间',
    reject_reason     VARCHAR(300)          COMMENT '退回原因',
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    locked_at         DATETIME              COMMENT '提交锁定时间',
    active_flag       BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'DRAFT' THEN order_id ELSE NULL END) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_version_no (order_id, version_no),
    UNIQUE KEY uk_active_record (active_flag),
    KEY idx_rv_order_status (order_id, status),
    KEY idx_rv_source_version (source_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='结构化原始记录版本';

-- 3. 明细表
CREATE TABLE IF NOT EXISTS lab_record_item (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    version_id     BIGINT       NOT NULL,
    item_no        INT          NOT NULL,
    calibration_point VARCHAR(80) NOT NULL COMMENT '校准点',
    standard_value VARCHAR(80)  NOT NULL COMMENT '标准值',
    indicated_value VARCHAR(80) NOT NULL COMMENT '示值',
    error_value    VARCHAR(80)  NOT NULL COMMENT '误差',
    unit           VARCHAR(24)  NOT NULL COMMENT '单位',
    remark         VARCHAR(120)           COMMENT '必要备注',
    created_at     DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_item_no (version_id, item_no),
    KEY idx_ri_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原始记录校准数据项';

-- 4. 留痕事件表
CREATE TABLE IF NOT EXISTS lab_record_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    order_id       BIGINT       NOT NULL,
    version_id     BIGINT       NOT NULL,
    event_type     VARCHAR(24)  NOT NULL,
    actor_name     VARCHAR(32)  NOT NULL,
    actor_role     VARCHAR(16)  NOT NULL,
    detail         VARCHAR(300),
    from_version_id BIGINT,
    created_at     DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_re_order_time (order_id, created_at),
    KEY idx_re_version (version_id),
    KEY idx_re_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原始记录版本操作留痕';

-- 5. 已存在于旧库且已经核验/出证/发放的文本数据，升级后只能只读保留；未核验仍为 LEGACY_TEXT，由接口确认转换。
UPDATE lab_calibration_order
SET record_mode = 'LEGACY_READONLY'
WHERE record_mode = 'LEGACY_TEXT'
  AND status IN ('VERIFYING', 'CERTIFYING', 'ISSUED');
