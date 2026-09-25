-- 系统基础配置 + 后端权限要求来源标记。
-- 当前处于个人项目早期阶段：所有现有用户统一绑定 OWNER，后续定型时再收紧默认角色。

ALTER TABLE sys_permission
    MODIFY COLUMN name VARCHAR(128) NOT NULL COMMENT '权限 / 菜单名称',
    ADD COLUMN backend_required TINYINT NOT NULL DEFAULT 0 COMMENT '当前后端代码是否声明该权限' AFTER status,
    ADD COLUMN last_seen_time DATETIME NULL COMMENT '最近一次被后端扫描到的时间' AFTER backend_required;

-- 新 glob 语义下，单独的 * 就代表匹配任意长度的全部文本。
UPDATE sys_permission SET perm = '*' WHERE id = 1;

CREATE TABLE sys_config (
    id           BIGINT        NOT NULL COMMENT '配置 ID（雪花）',
    config_key   VARCHAR(128)  NOT NULL COMMENT '配置键',
    config_value VARCHAR(1024) NOT NULL COMMENT '配置值（文本存储，按 value_type 解释）',
    value_type   VARCHAR(16)   NOT NULL DEFAULT 'STRING' COMMENT 'STRING / BOOLEAN / INTEGER',
    config_group VARCHAR(64)   NOT NULL DEFAULT 'SYSTEM' COMMENT '配置分组',
    name         VARCHAR(64)   NOT NULL COMMENT '配置名称',
    description  VARCHAR(255)  NULL COMMENT '配置说明',
    builtin      TINYINT       NOT NULL DEFAULT 0 COMMENT '内置配置不可删除',
    status       TINYINT       NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key),
    KEY idx_config_group (config_group)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统配置表';

INSERT INTO sys_config
    (id, config_key, config_value, value_type, config_group, name, description, builtin)
VALUES
    (1, 'auth.register.enabled', 'true', 'BOOLEAN', 'AUTH', '允许注册', '关闭后拒绝发送注册验证码和创建新账号', 1),
    (2, 'auth.login.enabled', 'true', 'BOOLEAN', 'AUTH', '允许登录', '关闭后拒绝签发新的登录令牌，不影响已有会话', 1),
    (3, 'auth.email-code.enabled', 'true', 'BOOLEAN', 'AUTH', '允许发送验证码', '注册和找回密码验证码的总开关', 1),
    (4, 'auth.password-reset.enabled', 'true', 'BOOLEAN', 'AUTH', '允许找回密码', '关闭后拒绝发送找回密码验证码和重置密码', 1);

-- 测试阶段所有用户都作为站长，统一拥有 glob 超级权限 *。
DELETE FROM sys_user_role;
INSERT INTO sys_user_role (user_id, role_id)
SELECT id, 3 FROM sys_user WHERE deleted = 0;
