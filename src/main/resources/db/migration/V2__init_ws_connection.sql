-- ============================================================================
--  V2 · WS 连接表
-- ----------------------------------------------------------------------------
--  设计要点（详见 docs/WS连接.md）：
--
--  ★ config 与 endpoint_path 的归属划分
--      config         —— 协议特有参数（AppID / token / …），完全由插件定义，
--                        Java 不解释字段含义（opaque）
--      endpoint_path  —— 传输层接入地址，Java 生成、用户不可改
--    这不是「协议假设」：Java 持有 WS 服务器，接入地址本来就归它管。
--
--  ★ endpoint_path 由 Java 生成（/ws/{32 位随机}）
--      生成即唯一 → 不需要运行时冲突判定，唯一性直接由 DB 唯一索引保证。
--      唯一索引建在**可空列**上：MySQL 允许多个 NULL 共存，
--      所以正向连接（endpoint_path = NULL）可以有任意多条。
--
--  ★ 表里不出现任何协议色彩的字段名
--      不出现 onebot / napcat / qq。方向也由 connection_type 决定，不单独设列。
--
--  ★ config 里标了 x-secret 的字段由应用层做**字段级加密**
--      注意是字段级而不是整列加密 —— 因为 Java 需要按 secretField 取出密钥值
--      来做握手校验（用元数据定位字段，不是理解语义）。
--
--  ★ owner_user_id 允许为空
--      原设计是 NOT NULL。但 auth 模块尚未落地，「当前调用者是谁」无法确定
--      （sys_user 里也还没有任何用户）。硬填一个假 ID 会污染数据，
--      而 NOT NULL 会让创建接口直接失败 —— 这两种结果都比允许 NULL 差。
--      auth 落地后：加一条迁移回填归属，再把列改回 NOT NULL。
-- ============================================================================

CREATE TABLE `ws_connection` (
    `id`              BIGINT       NOT NULL                COMMENT '雪花 ID',
    `name`            VARCHAR(64)  NOT NULL                COMMENT '连接名，工作流按名字引用',
    `connection_type` VARCHAR(64)  NOT NULL                COMMENT '连接类型，来自适配器声明；决定方向、表单、路由',
    `config`          JSON         NOT NULL                COMMENT '连接参数，完全由插件定义，Java 不解释字段含义',
    `endpoint_path`   VARCHAR(128)     NULL                COMMENT '反向接入路径，Java 生成、用户不可改；正向连接为 NULL',
    `owner_user_id`   BIGINT           NULL                COMMENT '所属用户；auth 落地前为 NULL，见文件头说明',
    `enabled`         TINYINT      NOT NULL DEFAULT 1      COMMENT '配置状态：1=启用 0=停用',
    `remark`          VARCHAR(255)     NULL                COMMENT '备注',
    `deleted`         TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删，1=已删',
    `create_by`       BIGINT           NULL                COMMENT '创建人 ID',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT           NULL                COMMENT '更新人 ID',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    UNIQUE KEY `uk_endpoint_path` (`endpoint_path`),
    KEY `idx_type` (`connection_type`),
    KEY `idx_owner` (`owner_user_id`),
    KEY `idx_enabled` (`enabled`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '通用 WS 连接定义（协议无关）';
