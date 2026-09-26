-- Loom development bootstrap schema.
-- The project is pre-release: this single Flyway migration is the canonical schema
-- and seed data. When the design changes during development, rebuild the database.

CREATE TABLE sys_user (
    id              BIGINT       NOT NULL COMMENT '用户 ID（雪花）',
    password        VARCHAR(255) NOT NULL COMMENT '密码哈希（BCrypt）',
    email           VARCHAR(128) NOT NULL COMMENT '邮箱（登录 / 找回密码 / 验证码）',
    phone           VARCHAR(32)  NULL,
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=停用',
    last_login_time DATETIME     NULL,
    last_login_ip   VARCHAR(64)  NULL,
    remark          VARCHAR(255) NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    create_by       BIGINT       NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT       NULL,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email),
    KEY idx_user_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

CREATE TABLE sys_role (
    id          BIGINT       NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    sort        INT          NOT NULL DEFAULT 0,
    status      TINYINT      NOT NULL DEFAULT 1,
    builtin     TINYINT      NOT NULL DEFAULT 0,
    remark      VARCHAR(255) NULL,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_by   BIGINT       NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by   BIGINT       NULL,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code),
    KEY idx_role_sort (sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色表';

CREATE TABLE sys_permission (
    id               BIGINT       NOT NULL COMMENT '权限 ID（雪花）',
    name             VARCHAR(128) NOT NULL COMMENT '权限名称',
    type             VARCHAR(16)  NOT NULL DEFAULT 'API' COMMENT 'API / BUTTON / DATA',
    perm             VARCHAR(128) NOT NULL COMMENT '权限串，支持 * 通配',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    backend_required TINYINT      NOT NULL DEFAULT 0 COMMENT '是否由后端代码声明',
    last_seen_time   DATETIME     NULL COMMENT '最近一次被后端扫描到的时间',
    remark           VARCHAR(255) NULL,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    create_by        BIGINT       NULL,
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by        BIGINT       NULL,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_perm (perm),
    KEY idx_permission_type (type),
    KEY idx_permission_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限定义表';

CREATE TABLE sys_menu (
    id                     BIGINT       NOT NULL COMMENT '菜单 ID（雪花）',
    parent_id              BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单 ID，0=顶级',
    type                   VARCHAR(16)  NOT NULL DEFAULT 'MENU' COMMENT 'CATALOG / MENU',
    name                   VARCHAR(128) NOT NULL COMMENT '菜单名称',
    route_name             VARCHAR(128) NULL COMMENT '前端路由名称',
    path                   VARCHAR(255) NULL COMMENT '前端路由地址',
    component_key          VARCHAR(128) NULL COMMENT '前端组件注册 key',
    icon_key               VARCHAR(64)  NULL COMMENT '前端图标 key',
    redirect               VARCHAR(255) NULL COMMENT '目录默认跳转地址',
    sort                   INT          NOT NULL DEFAULT 0,
    visible                TINYINT      NOT NULL DEFAULT 1 COMMENT '1=显示 0=隐藏',
    keep_alive             TINYINT      NOT NULL DEFAULT 0,
    status                 TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    remark                 VARCHAR(255) NULL,
    deleted                TINYINT      NOT NULL DEFAULT 0,
    create_by              BIGINT       NULL,
    create_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by              BIGINT       NULL,
    update_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_menu_route_name (route_name),
    KEY idx_menu_parent (parent_id),
    KEY idx_menu_sort (sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '菜单树表';

CREATE TABLE sys_user_role (
    user_id     BIGINT   NOT NULL,
    role_id     BIGINT   NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id),
    KEY idx_user_role_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户-角色关联表';

CREATE TABLE sys_role_permission (
    role_id       BIGINT   NOT NULL,
    permission_id BIGINT   NOT NULL,
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id),
    KEY idx_role_permission_permission (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色-权限关联表';

CREATE TABLE sys_role_menu (
    role_id     BIGINT   NOT NULL,
    menu_id     BIGINT   NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, menu_id),
    KEY idx_role_menu_menu (menu_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色-菜单关联表';

CREATE TABLE ws_connection (
    id              BIGINT       NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    connection_type VARCHAR(64)  NOT NULL,
    config          JSON         NOT NULL,
    endpoint_path   VARCHAR(128) NULL,
    owner_user_id   BIGINT       NULL,
    enabled         TINYINT      NOT NULL DEFAULT 1,
    remark          VARCHAR(255) NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    create_by       BIGINT       NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT       NULL,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_connection_owner_name (owner_user_id, name),
    UNIQUE KEY uk_connection_endpoint (endpoint_path),
    KEY idx_connection_type (connection_type),
    KEY idx_connection_owner (owner_user_id),
    KEY idx_connection_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '通用 WS 连接定义';

CREATE TABLE notify_log (
    id          BIGINT       NOT NULL,
    biz_type    VARCHAR(32)  NOT NULL,
    recipient   VARCHAR(128) NOT NULL,
    subject     VARCHAR(255) NULL,
    status      VARCHAR(16)  NOT NULL,
    error       VARCHAR(512) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_notify_recipient (recipient),
    KEY idx_notify_biz_type (biz_type),
    KEY idx_notify_create_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '通知发送日志';

CREATE TABLE sys_config (
    id           BIGINT        NOT NULL,
    config_key   VARCHAR(128)  NOT NULL,
    config_value VARCHAR(1024) NOT NULL,
    value_type   VARCHAR(16)   NOT NULL DEFAULT 'STRING',
    config_group VARCHAR(64)   NOT NULL DEFAULT 'SYSTEM',
    name         VARCHAR(64)   NOT NULL,
    description  VARCHAR(255)  NULL,
    builtin      TINYINT       NOT NULL DEFAULT 0,
    status       TINYINT       NOT NULL DEFAULT 1,
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key),
    KEY idx_config_group (config_group)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统配置表';

INSERT INTO sys_role (id, code, name, sort, status, builtin, remark) VALUES
    (1, 'USER', '用户', 30, 1, 1, '默认角色'),
    (2, 'ADMIN', '管理员', 20, 1, 1, '日常运营管理'),
    (3, 'OWNER', '站长', 10, 1, 1, '最高权限');

INSERT INTO sys_permission (id, name, type, perm, status, backend_required, remark) VALUES
    (1, '超级权限', 'API', '*:*:*', 1, 0, '三段式 glob 通配全部权限，仅授予站长'),
    (2, '查看权限目录', 'API', 'system:permission:list', 1, 1, '权限管理入口'),
    (3, '更新权限状态', 'API', 'system:permission:update', 1, 1, '权限管理入口'),
    (4, '查看菜单', 'API', 'system:menu:list', 1, 1, '菜单管理入口'),
    (5, '创建菜单', 'API', 'system:menu:create', 1, 1, '菜单管理入口'),
    (6, '更新菜单', 'API', 'system:menu:update', 1, 1, '菜单管理入口'),
    (7, '删除菜单', 'API', 'system:menu:delete', 1, 1, '菜单管理入口'),
    (8, '查看用户角色', 'API', 'system:user:list', 1, 1, '权限关系管理'),
    (9, '更新用户角色', 'API', 'system:user:update', 1, 1, '权限关系管理'),
    (10, '查看角色授权', 'API', 'system:role:list', 1, 1, '权限关系管理'),
    (11, '更新角色授权', 'API', 'system:role:update', 1, 1, '权限关系管理');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES (3, 1);

INSERT INTO sys_config
    (id, config_key, config_value, value_type, config_group, name, description, builtin)
VALUES
    (1, 'auth.register.enabled', 'true', 'BOOLEAN', 'AUTH', '允许注册', '关闭后拒绝发送注册验证码和创建新用户', 1),
    (2, 'auth.login.enabled', 'true', 'BOOLEAN', 'AUTH', '允许登录', '关闭后拒绝签发新的登录令牌，不影响已有会话', 1),
    (3, 'auth.email-code.enabled', 'true', 'BOOLEAN', 'AUTH', '允许发送验证码', '注册和找回密码验证码的总开关', 1),
    (4, 'auth.password-reset.enabled', 'true', 'BOOLEAN', 'AUTH', '允许找回密码', '关闭后拒绝发送找回密码验证码和重置密码', 1);

INSERT INTO sys_menu
    (id, parent_id, type, name, route_name, path, component_key, icon_key, sort, visible, keep_alive, status, remark)
VALUES
    (1001, 0, 'MENU', '权限管理', 'system-permissions', '/system/permissions', 'system.permissions', 'shield', 10, 1, 0, 1, '用户、角色与权限关系管理'),
    (1002, 0, 'MENU', '菜单管理', 'system-menus', '/system/menus', 'system.menus', 'layout', 20, 1, 0, 1, '菜单树管理');

INSERT INTO sys_role_menu (role_id, menu_id) VALUES (3, 1001), (3, 1002);
