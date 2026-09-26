-- ============================================================================
-- Loom · 单文件初始化脚本
-- ============================================================================
--
-- 项目还没定型，表结构会频繁改。所以这里只有**一个**迁移文件：建表 + 内置测试数据。
-- 改结构的流程是「改这个文件 → 重启」—— 应用启动时会把库清空重建（见 application.yml 的
-- loom.datasource.recreate 开关），不需要再写 V5、V6… 一大堆增量脚本。
--
-- 代价说清楚：每次启动数据都没了（新建的菜单、改过的配置）。这是刻意的选择，
-- 换来的是「改表不用写迁移」。等结构定型后关掉重建开关即可，那时再回到增量迁移的写法。
--
-- ⚠️ 重建只在 DB_HOST 是本机时允许，连到别的主机会直接拒绝启动。见 application.yml。
--
-- 已应用的迁移文件不可再改这条规矩在这里**不适用**：库每次都被清掉，
-- flyway_schema_history 一并清空，历史记录和文件永远同步。


-- ---------------------------------------------------------------------------
-- 用户 / 角色 / 权限
-- ---------------------------------------------------------------------------

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
    perm             VARCHAR(128) NOT NULL COMMENT '权限串，三段式 glob',
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

-- ---------------------------------------------------------------------------
-- 菜单
-- ---------------------------------------------------------------------------

CREATE TABLE sys_menu (
    id            BIGINT       NOT NULL COMMENT '菜单 ID（雪花）',
    parent_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单 ID，0=顶级',
    type          VARCHAR(16)  NOT NULL DEFAULT 'MENU' COMMENT 'CATALOG / MENU',
    name          VARCHAR(128) NOT NULL COMMENT '菜单名称',
    route_name    VARCHAR(128) NULL COMMENT '前端路由名称',
    path          VARCHAR(255) NULL COMMENT '前端路由地址',
    component_key VARCHAR(128) NULL COMMENT '前端组件注册 key',
    icon_key      VARCHAR(64)  NULL COMMENT '前端图标 key',
    redirect      VARCHAR(255) NULL COMMENT '目录默认跳转地址',
    sort          INT          NOT NULL DEFAULT 0,
    visible       TINYINT      NOT NULL DEFAULT 1 COMMENT '1=显示 0=隐藏',
    keep_alive    TINYINT      NOT NULL DEFAULT 0,
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    remark        VARCHAR(255) NULL,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    create_by     BIGINT       NULL,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by     BIGINT       NULL,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_menu_route_name (route_name),
    KEY idx_menu_parent (parent_id),
    KEY idx_menu_sort (sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '菜单树表';

-- ---------------------------------------------------------------------------
-- 关系表
-- ---------------------------------------------------------------------------

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

-- ---------------------------------------------------------------------------
-- 系统配置
-- ---------------------------------------------------------------------------
--
-- 一条配置 = 一个键 + 一个 JSON 值。值统一是 **JSON 对象**，形状由每条配置自己定：
--
--   {"enabled": true}                        纯开关（有 enabled 就有「关闭」这个概念）
--   {"enabled": true, "seconds": 30}         开关 + 参数
--   {"enabled": true, "maxUsers": 3}         开关 + 配额
--
-- 没有类型列（JSON 自己带类型），没有 status 列（值里的 enabled 就是开关）。
-- 「这条允不允许被关掉」也是**数据**：不要给它配 enabled 即可；核心配置在
-- SystemConfigService 里额外声明一条规则，防止有人把它关成不可恢复的状态。
--
-- 列用 TEXT 而不是 MySQL 原生 JSON：校验权威在应用层，值不合法时要能当成一条
-- 可在配置页上修复的脏数据显示出来，而不是让写入阶段被数据库直接拒绝。

CREATE TABLE sys_config (
    id           BIGINT       NOT NULL,
    config_key   VARCHAR(128) NOT NULL,
    config_value TEXT         NOT NULL COMMENT '配置值，JSON 对象文本',
    config_group VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM',
    name         VARCHAR(64)  NOT NULL,
    description  VARCHAR(255) NULL,
    builtin      TINYINT      NOT NULL DEFAULT 0,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key),
    KEY idx_config_group (config_group)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统配置表';

-- ---------------------------------------------------------------------------
-- 连接与通知
-- ---------------------------------------------------------------------------

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


-- ============================================================================
-- 内置测试数据
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 内置站长账号
-- ---------------------------------------------------------------------------
--
-- 邮箱 admin@loom.local / 密码 LoomAdmin123
--
-- 哈希是 BCrypt 的，用 spring-security-crypto 的 BCrypt.hashpw 生成（不是
-- BCryptPasswordEncoder —— 两者的前缀与强度参数写法不同，混用会登录不上）。
-- 这是**开发用**账号，密码写在仓库里；正式环境必须删掉或改密。

INSERT INTO sys_user (id, password, email, status, remark) VALUES
    (1, '$2a$10$eMr62ErnaBXBe4jCO0WCOu/08/y6bvRv0WNpFf40gR.7z.9ANb.9a',
     'admin@loom.local', 1, '内置开发站长账号，密码 LoomAdmin123');

-- ---------------------------------------------------------------------------
-- 角色与权限
-- ---------------------------------------------------------------------------

INSERT INTO sys_role (id, code, name, sort, status, builtin, remark) VALUES
    (1, 'USER', '用户', 30, 1, 1, '默认角色'),
    (2, 'ADMIN', '管理员', 20, 1, 1, '日常运营管理'),
    (3, 'OWNER', '站长', 10, 1, 1, '最高权限');

-- 权限目录。后端启动时会扫描 @PreAuthorize 并补齐缺失项，这里列出的只是**入口权限**：
-- 名字与分组是给人看的，写在这里免得第一次启动时权限页一片空白。
-- 注意：扫描的 upsert 会把 name 重置成权限串本身，所以这些中文名在启动后会被覆盖 ——
-- 这是已知偏差，见《菜单与权限当前实现.md》。
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
    (11, '更新角色授权', 'API', 'system:role:update', 1, 1, '权限关系管理'),
    (12, '查看系统配置', 'API', 'system:config:list', 1, 1, '系统配置入口'),
    (13, '更新系统配置', 'API', 'system:config:update', 1, 1, '系统配置入口');

-- 站长只绑这一行超级权限，以后新增权限点自动拥有
INSERT INTO sys_role_permission (role_id, permission_id) VALUES (3, 1);

-- 内置账号 → 站长
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 3);

-- ---------------------------------------------------------------------------
-- 菜单
-- ---------------------------------------------------------------------------
--
-- 目录不写 route_name / path：前端路由表是静态声明的，目录本身不可点击
-- （侧边栏把有子项的 CATALOG 渲染成折叠按钮）。给它编一个 path 反而会让
-- 「子项被删空」的目录变成一条指向不存在页面的链接。

INSERT INTO sys_menu
    (id, parent_id, type, name, route_name, path, component_key, icon_key, redirect,
     sort, visible, keep_alive, status, remark)
VALUES
    (1000, 0,    'CATALOG', '系统管理', NULL, NULL, NULL, 'settings', NULL,
     10, 1, 0, 1, '系统级配置与权限管理'),
    (1001, 1000, 'MENU', '权限管理', 'system-permissions', '/system/permissions',
     'system.permissions', 'shield', NULL, 10, 1, 0, 1, '用户、角色与权限关系管理'),
    (1002, 1000, 'MENU', '菜单管理', 'system-menus', '/system/menus',
     'system.menus', 'layout', NULL, 20, 1, 0, 1, '菜单树管理'),
    (1003, 1000, 'MENU', '系统配置', 'system-config', '/system/config',
     'system.config', 'sliders', NULL, 30, 1, 0, 1, '注册、登录、验证码等系统开关');

-- 站长要能看到**目录本身**：navigation() 只纳入「父节点也可见」的条目，
-- 缺了 (3, 1000) 这一行，三个子菜单会因为父级不可见被连坐隐藏，侧边栏直接空掉。
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (3, 1000), (3, 1001), (3, 1002), (3, 1003);

-- ---------------------------------------------------------------------------
-- 系统配置
-- ---------------------------------------------------------------------------
--
-- 值统一是 JSON 对象。带 enabled 的表示「这个功能有开关」；纯参数配置不带 enabled。

INSERT INTO sys_config (id, config_key, config_value, config_group, name, description, builtin) VALUES
    (1, 'auth.register.enabled', '{"enabled": true}', 'AUTH', '允许注册',
     '关闭后拒绝发送注册验证码和创建新用户', 1),
    (2, 'auth.login.enabled', '{"enabled": true}', 'AUTH', '允许登录',
     '关闭后拒绝签发新的登录令牌，不影响已有会话。关掉会让所有人都登不进来', 1),
    (3, 'auth.email-code.enabled', '{"enabled": true}', 'AUTH', '允许发送验证码',
     '注册和找回密码验证码的总开关', 1),
    (4, 'auth.password-reset.enabled', '{"enabled": true}', 'AUTH', '允许找回密码',
     '关闭后拒绝发送找回密码验证码和重置密码', 1);
