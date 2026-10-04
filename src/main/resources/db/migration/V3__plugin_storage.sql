-- 插件持久化：结构化状态与文件元数据放 MySQL，文件内容走对象存储。
-- Redis 只承担跨进程 / 多实例锁，既不存结构化状态，也不是真相来源。
--
-- 两张表都以 (plugin_key, scope, scope_id) 作为隔离前缀，与插件看到的
-- `ctx.storage.plugin` / `ctx.storage.connection` / `ctx.storage.ephemeral`
-- 三种作用域一一对应。插件永远不直接看到表名、路径或 Redis key。

-- ============================================================
-- 结构化状态（KV）
-- ============================================================
-- 值统一存 JSON：插件写进去的是任意 Python 值，只有 JSON 能无损承载。
-- value_version 是跨请求乐观锁的载体，expected_version 就是拿它比对。
-- expires_at 为 NULL 表示永不过期；过期行在读取时惰性删除，另有定时清理兜底。
CREATE TABLE plugin_state (
    plugin_key    VARCHAR(128) NOT NULL,
    scope         VARCHAR(16)  NOT NULL,
    scope_id      VARCHAR(64)  NOT NULL DEFAULT '0',
    state_key     VARCHAR(255) NOT NULL,
    value_json    JSON         NOT NULL,
    value_version BIGINT       NOT NULL DEFAULT 1,
    expires_at    DATETIME     NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (plugin_key, scope, scope_id, state_key),
    KEY idx_plugin_state_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '插件持久化 JSON 状态';

-- ============================================================
-- 文件元数据
-- ============================================================
-- 这里不存文件内容，只存"这个引用指向哪个对象"。换成本地目录还是 R2/S3，
-- 变的只是 object_key 怎么解释，表结构不动。
CREATE TABLE plugin_file (
    id           BIGINT        NOT NULL,
    plugin_key   VARCHAR(128)  NOT NULL,
    scope        VARCHAR(16)   NOT NULL,
    scope_id     VARCHAR(64)   NOT NULL DEFAULT '0',
    file_ref     VARCHAR(512)  NOT NULL,
    object_key   VARCHAR(1024) NOT NULL,
    content_type VARCHAR(128)  NULL,
    size_bytes   BIGINT        NOT NULL,
    sha256       CHAR(64)      NOT NULL,
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plugin_file (plugin_key, scope, scope_id, file_ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '插件对象存储文件元数据';
