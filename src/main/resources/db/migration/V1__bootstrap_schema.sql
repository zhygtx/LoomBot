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

-- status 是**全库唯一**保留的状态列：它是账号级开关（封号 / 解封），
-- 语义不是「这条记录还算不算数」，而是「这个人还能不能用」。
-- 其余表的 status 已全部删除，见文件末尾「为什么没有 deleted，也没有 status」。
CREATE TABLE sys_user (
    id              BIGINT       NOT NULL COMMENT '用户 ID（雪花）',
    password        VARCHAR(255) NOT NULL COMMENT '密码哈希（BCrypt）',
    email           VARCHAR(128) NOT NULL COMMENT '邮箱（登录 / 找回密码 / 验证码）',
    phone           VARCHAR(32)  NULL,
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=停用（封号）',
    last_login_time DATETIME     NULL,
    last_login_ip   VARCHAR(64)  NULL,
    remark          VARCHAR(255) NULL,
    create_by       BIGINT       NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT       NULL,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email),
    KEY idx_user_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

-- 没有 status：停用一个角色和「删除它」没有区别 —— 它本来就是一组权限的集合，
-- 不想让人用就把它从用户身上摘掉。留着 status 只会多出一个「已停用但还绑着一堆用户」的
-- 中间态，而那个状态下权限到底算不算数，谁都得再想一遍。
CREATE TABLE sys_role (
    id          BIGINT       NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    sort        INT          NOT NULL DEFAULT 0,
    builtin     TINYINT      NOT NULL DEFAULT 0,
    remark      VARCHAR(255) NULL,
    create_by   BIGINT       NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by   BIGINT       NULL,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code),
    KEY idx_role_sort (sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色表';

-- 没有 status：权限的「启用 / 停用」是**授权**问题，不是权限定义自己的属性。
-- 想让某个权限暂时不生效，应该去角色授权里取消勾选，而不是把目录里的定义停用 ——
-- 后者会让所有角色（包括持有 *:*:* 的站长）一起失灵，是全局性的副作用。
CREATE TABLE sys_permission (
    id               BIGINT       NOT NULL COMMENT '权限 ID（雪花）',
    name             VARCHAR(128) NOT NULL COMMENT '权限名称',
    type             VARCHAR(16)  NOT NULL DEFAULT 'API' COMMENT 'API / BUTTON / DATA',
    perm             VARCHAR(128) NOT NULL COMMENT '权限串，三段式 glob',
    backend_required TINYINT      NOT NULL DEFAULT 0 COMMENT '是否由后端代码声明',
    last_seen_time   DATETIME     NULL COMMENT '最近一次被后端扫描到的时间',
    remark           VARCHAR(255) NULL,
    create_by        BIGINT       NULL,
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by        BIGINT       NULL,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_perm (perm),
    KEY idx_permission_type (type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限定义表';

-- ---------------------------------------------------------------------------
-- 菜单
-- ---------------------------------------------------------------------------

-- 没有 status：菜单的「停用」和「删除」在结果上是同一件事（侧边栏不出现、路由进不去），
-- 于是它只多提供了一个「藏起来但还能被角色勾选」的中间态。
-- 不想让人看见就把 visible 关掉（明确表达「隐藏」），不想让它存在就删掉。
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
    remark        VARCHAR(255) NULL,
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
-- 插件注册表
-- ---------------------------------------------------------------------------
--
-- 元数据同步和运行时加载是两件事：
--   · 启动时扫描公共插件仓库，登记全部插件版本；
--   · 连接只引用具体 plugin_version_id；
--   · 适配器进程只为被启用连接引用的版本启动。
--
-- 这里没有 enabled / current_version_id / plugin_permission。

CREATE TABLE plugin_repository (
    id               BIGINT        NOT NULL,
    repo_key         VARCHAR(64)   NOT NULL,
    repo_url         VARCHAR(512)  NULL,
    branch           VARCHAR(128)  NOT NULL DEFAULT 'main',
    local_path       VARCHAR(512)  NOT NULL,
    last_commit_hash VARCHAR(128)  NULL,
    last_pull_time   DATETIME      NULL,
    last_scan_time   DATETIME      NULL,
    last_error       VARCHAR(1024) NULL,
    create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plugin_repository_key (repo_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '插件仓库同步状态';

CREATE TABLE plugin (
    id            BIGINT       NOT NULL,
    repository_id BIGINT       NOT NULL,
    plugin_key    VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512) NULL,
    homepage      VARCHAR(512) NULL,
    author        VARCHAR(128) NULL,
    sort          INT          NOT NULL DEFAULT 0,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plugin_key (plugin_key),
    KEY idx_plugin_repository (repository_id),
    KEY idx_plugin_sort (sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '插件稳定身份';

CREATE TABLE plugin_version (
    id                 BIGINT       NOT NULL,
    plugin_id          BIGINT       NOT NULL,
    version            VARCHAR(64)  NOT NULL,
    source_commit_hash VARCHAR(128) NOT NULL,
    manifest_json      JSON         NOT NULL,
    manifest_sha256    CHAR(64)     NOT NULL,
    artifact_sha256    CHAR(64)     NOT NULL,
    install_path       VARCHAR(512) NOT NULL,
    entry_point        VARCHAR(255) NULL,
    python_path         VARCHAR(512) NULL,
    runtime_key        VARCHAR(128) NOT NULL,
    published_time     DATETIME     NULL,
    synced_time        DATETIME     NOT NULL,
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plugin_version (plugin_id, version),
    UNIQUE KEY uk_plugin_version_runtime_key (runtime_key),
    KEY idx_plugin_version_plugin (plugin_id),
    KEY idx_plugin_version_artifact (artifact_sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '已同步的不可变插件版本';

CREATE TABLE plugin_capability (
    id                BIGINT       NOT NULL,
    plugin_version_id BIGINT       NOT NULL,
    capability        VARCHAR(128) NOT NULL,
    kind              VARCHAR(32)  NOT NULL,
    target            VARCHAR(64)  NULL,
    detail_json       JSON         NULL,
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plugin_capability (plugin_version_id, capability),
    KEY idx_plugin_capability_kind (kind),
    KEY idx_plugin_capability_target (target)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '插件能力声明';

CREATE TABLE plugin_connection_type (
    id                    BIGINT       NOT NULL,
    plugin_version_id     BIGINT       NOT NULL,
    connection_type       VARCHAR(64)  NOT NULL,
    entry_point           VARCHAR(255) NOT NULL DEFAULT 'main.py',
    display_name          VARCHAR(128) NOT NULL,
    direction             VARCHAR(16)  NOT NULL,
    protocol_version      VARCHAR(32)  NOT NULL,
    schema_version        VARCHAR(32)  NULL,
    config_schema         JSON         NOT NULL,
    config_schema_sha256  CHAR(64)     NOT NULL,
    sort                  INT          NOT NULL DEFAULT 0,
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plugin_connection_type (plugin_version_id, connection_type),
    KEY idx_plugin_connection_type_name (connection_type),
    KEY idx_plugin_connection_direction (direction)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '插件版本声明的连接类型';

CREATE TABLE plugin_node (
    id                BIGINT       NOT NULL,
    plugin_version_id BIGINT       NOT NULL,
    node_key          VARCHAR(128) NOT NULL,
    node_type         VARCHAR(16)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    description       VARCHAR(512) NULL,
    input_schema      JSON         NULL,
    output_schema     JSON         NULL,
    source_ref        VARCHAR(255) NULL,
    sort              INT          NOT NULL DEFAULT 0,
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plugin_node (plugin_version_id, node_key),
    KEY idx_plugin_node_key (node_key),
    KEY idx_plugin_node_type (node_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '插件版本自省出的节点目录';

CREATE TABLE plugin_dependency (
    id                BIGINT       NOT NULL,
    plugin_version_id BIGINT       NOT NULL,
    package_name      VARCHAR(128) NOT NULL,
    version_spec      VARCHAR(128) NULL,
    resolved_version  VARCHAR(64)  NULL,
    wheel_sha256      CHAR(64)     NULL,
    source_url        VARCHAR(512) NULL,
    marker            VARCHAR(128) NULL,
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plugin_dependency (plugin_version_id, package_name, marker)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '插件版本锁定依赖';

-- ---------------------------------------------------------------------------
-- 工作流定义与执行记录
-- ---------------------------------------------------------------------------

CREATE TABLE workflow_info (
    id                 BIGINT       NOT NULL,
    name               VARCHAR(128) NOT NULL,
    description        VARCHAR(512) NULL,
    enabled            TINYINT      NOT NULL DEFAULT 1,
    current_version_id BIGINT       NULL,
    owner_user_id      BIGINT       NOT NULL,
    create_by          BIGINT       NULL,
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by          BIGINT       NULL,
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_owner_name (owner_user_id, name),
    KEY idx_workflow_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流稳定身份';

CREATE TABLE workflow_version (
    id          BIGINT       NOT NULL,
    workflow_id BIGINT       NOT NULL,
    version_no  INT          NOT NULL,
    definition  JSON         NOT NULL,
    remark      VARCHAR(512) NULL,
    create_by   BIGINT       NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_version (workflow_id, version_no),
    KEY idx_workflow_version_workflow (workflow_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '不可变工作流定义版本';

-- 执行结果只有 SUCCESS / TIMEOUT / FAILED。
-- 崩溃中断属于 FAILED，用 error_code=INTERRUPTED 区分，不增加第四种状态。
CREATE TABLE workflow_execution (
    id                       BIGINT       NOT NULL,
    execution_id             VARCHAR(64)  NOT NULL,
    workflow_id              BIGINT       NOT NULL,
    definition_version       INT          NOT NULL,
    connection_id            BIGINT       NOT NULL,
    adapter_plugin_version_id BIGINT      NOT NULL,
    connection_type          VARCHAR(64)  NOT NULL,
    node_key                 VARCHAR(128) NOT NULL,
    group_id                 VARCHAR(64)  NULL,
    event_summary            VARCHAR(1024) NULL,
    status                   VARCHAR(16)  NOT NULL,
    error_code               VARCHAR(64)  NULL,
    error_message            VARCHAR(2048) NULL,
    detail_json              JSON         NULL,
    detail_truncated         TINYINT      NOT NULL DEFAULT 0,
    start_time               DATETIME(3)  NOT NULL,
    end_time                 DATETIME(3)  NULL,
    duration_ms              BIGINT       NULL,
    created_date             DATE         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_execution_id (execution_id),
    KEY idx_workflow_execution_workflow_time (workflow_id, start_time),
    KEY idx_workflow_execution_status_time (status, start_time),
    KEY idx_workflow_execution_connection_time (connection_id, start_time),
    KEY idx_workflow_execution_cleanup (created_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流执行主记录';

CREATE TABLE workflow_execution_daily (
    biz_date        DATE         NOT NULL,
    connection_id   BIGINT       NOT NULL,
    group_id        VARCHAR(64)  NOT NULL DEFAULT '',
    workflow_id     BIGINT       NOT NULL,
    total_count     BIGINT       NOT NULL DEFAULT 0,
    success_count   BIGINT       NOT NULL DEFAULT 0,
    timeout_count   BIGINT       NOT NULL DEFAULT 0,
    failed_count    BIGINT       NOT NULL DEFAULT 0,
    avg_duration_ms BIGINT       NULL,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (biz_date, connection_id, group_id, workflow_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流执行日汇总';

-- ---------------------------------------------------------------------------
-- 连接与通知
-- ---------------------------------------------------------------------------

CREATE TABLE connection_definition (
    id              BIGINT       NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    plugin_version_id BIGINT     NOT NULL,
    connection_type VARCHAR(64)  NOT NULL,
    config          JSON         NOT NULL,
    endpoint_path   VARCHAR(128) NULL,
    owner_user_id   BIGINT       NULL,
    enabled         TINYINT      NOT NULL DEFAULT 1,
    desired_revision BIGINT      NOT NULL DEFAULT 1,
    remark          VARCHAR(255) NULL,
    create_by       BIGINT       NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT       NULL,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_connection_owner_name (owner_user_id, name),
    UNIQUE KEY uk_connection_endpoint (endpoint_path),
    KEY idx_connection_plugin_version (plugin_version_id),
    KEY idx_connection_type (connection_type),
    KEY idx_connection_owner (owner_user_id),
    KEY idx_connection_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '连接定义与期望状态';

-- Adapter 实际状态的只读投影。真相仍由 Python Adapter 运行时持有，Java 定时批量拉取后写入这里；
-- API 和前端只读这份快照，不再在用户请求线程里访问 Adapter。
CREATE TABLE connection_observation (
    connection_id       BIGINT        NOT NULL,
    instance_id         VARCHAR(64)   NULL,
    state               VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    runtime_reachable   TINYINT       NOT NULL DEFAULT 0,
    failure_reason      VARCHAR(1024) NULL,
    observed_revision   BIGINT        NULL,
    last_frame_at       BIGINT        NOT NULL DEFAULT 0,
    last_synced_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (connection_id),
    KEY idx_connection_runtime_state (state),
    KEY idx_connection_runtime_synced (last_synced_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '连接运行状态观测投影';

CREATE TABLE runtime_command_outbox (
    id                  BIGINT        NOT NULL,
    connection_id       BIGINT        NOT NULL,
    desired_revision    BIGINT        NOT NULL,
    command_kind        VARCHAR(32)   NOT NULL,
    payload             JSON          NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempt_count       INT           NOT NULL DEFAULT 0,
    next_attempt_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error          VARCHAR(1024) NULL,
    created_time        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_runtime_command_revision (connection_id, desired_revision, command_kind),
    KEY idx_runtime_command_pending (status, next_attempt_at),
    KEY idx_runtime_command_connection (connection_id, desired_revision)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '适配器期望命令投递箱';

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

INSERT INTO sys_role (id, code, name, sort, builtin, remark) VALUES
    (1, 'USER', '用户', 30, 1, '默认角色'),
    (2, 'ADMIN', '管理员', 20, 1, '日常运营管理'),
    (3, 'OWNER', '站长', 10, 1, '最高权限');

-- 权限目录。后端启动时会扫描 @PreAuthorize 并补齐缺失项，这里列出的只是**入口权限**：
-- 名字与分组是给人看的，写在这里免得第一次启动时权限页一片空白。
-- 注意：扫描的 upsert 会把 name 重置成权限串本身，所以这些中文名在启动后会被覆盖 ——
-- 这是已知偏差，见《菜单与权限当前实现.md》。
INSERT INTO sys_permission (id, name, type, perm, backend_required, remark) VALUES
    (1, '超级权限', 'API', '*:*:*', 0, '三段式 glob 通配全部权限，仅授予站长'),
    (2, '查看权限目录', 'API', 'system:permission:list', 1, '权限管理入口'),
    (3, '删除权限定义', 'API', 'system:permission:update', 1, '权限管理入口'),
    (4, '查看菜单', 'API', 'system:menu:list', 1, '菜单管理入口'),
    (5, '创建菜单', 'API', 'system:menu:create', 1, '菜单管理入口'),
    (6, '更新菜单', 'API', 'system:menu:update', 1, '菜单管理入口'),
    (7, '删除菜单', 'API', 'system:menu:delete', 1, '菜单管理入口'),
    (8, '查看用户角色', 'API', 'system:user:list', 1, '权限关系管理'),
    (9, '更新用户角色', 'API', 'system:user:update', 1, '权限关系管理'),
    (10, '查看角色授权', 'API', 'system:role:list', 1, '权限关系管理'),
    (11, '更新角色授权', 'API', 'system:role:update', 1, '权限关系管理'),
    (12, '查看系统配置', 'API', 'system:config:list', 1, '系统配置入口'),
    (13, '更新系统配置', 'API', 'system:config:update', 1, '系统配置入口');

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
     sort, visible, keep_alive, remark)
VALUES
    (1000, 0,    'CATALOG', '系统管理', NULL, NULL, NULL, 'settings', NULL,
     10, 1, 0, '系统级配置与权限管理'),
    (1001, 1000, 'MENU', '权限管理', 'system-permissions', '/system/permissions',
     'system.permissions', 'shield', NULL, 10, 1, 0, '用户、角色与权限关系管理'),
    (1002, 1000, 'MENU', '菜单管理', 'system-menus', '/system/menus',
     'system.menus', 'layout', NULL, 20, 1, 0, '菜单树管理'),
    (1003, 1000, 'MENU', '系统配置', 'system-config', '/system/config',
     'system.config', 'sliders', NULL, 30, 1, 0, '注册、登录、验证码等系统开关'),
    (1100, 0, 'MENU', '连接测试台', 'connections', '/connections',
     'connections.playground', 'network', NULL, 20, 1, 0, '创建插件连接并验证反向/正向适配器');

-- 站长要能看到**目录本身**：navigation() 只纳入「父节点也可见」的条目，
-- 缺了 (3, 1000) 这一行，三个子菜单会因为父级不可见被连坐隐藏，侧边栏直接空掉。
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (3, 1000), (3, 1001), (3, 1002), (3, 1003), (3, 1100);

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


-- ============================================================================
-- 为什么没有 deleted，也没有 status
-- ============================================================================
--
-- 两条都取消了：**删除就是真删**（DELETE 语句），**状态列只保留 sys_user.status**。
--
-- 【一】取消逻辑删除
--
-- 逻辑删除（deleted = 1 + 所有查询自动加 deleted = 0）在开发期是纯负债：
--
--   · 每一条查询都得记得「未删除」这个条件。漏了不会报错，只会多出几条幽灵数据 ——
--     这是最容易漏、也最难发现的一类 bug。
--   · 唯一索引和它打架。sys_user.email、sys_menu.route_name、connection_definition.name
--     都是唯一键，删掉的行还占着值，于是「删了却建不回来」。想绕开就得把唯一键
--     改成 (col, deleted) 联合索引，而那又要求删除时写进一个「每次都不一样的 deleted」——
--     D36 就是这么踩进去的，测试清理堆了 81 行永久残留。
--   · 项目自身的操作路径本来也没有「回收站」：菜单删了就是删了，没有恢复入口。
--     于是逻辑删除唯一兑现出来的东西，就是上面两条代价。
--
-- 真删除之后，「未删除」这个条件从所有 SQL 里消失，唯一索引恢复成普通的唯一约束。
-- 代价是删不回来 —— 这在当前阶段是可接受的，早期库本来每次启动都会重建。
--
-- 【二】status 只留 sys_user
--
-- sys_user.status 和其余几张表的 status 不是一回事：
--
--   · sys_user.status 是**账号开关**（封号 / 解封）。它不表示「这行记录还算不算数」，
--     表示「这个人还能不能用」。用户已经产生的数据（角色绑定、审计字段）必须留着，
--     所以不能靠删行表达。
--   · sys_menu / sys_permission / sys_role 的 status 是**配置开关**，而配置项的
--     「关掉」和「删掉」在结果上没有任何区别 —— 侧边栏同样不出现、接口同样 403。
--     多一个 status 只多出一个中间态，以及一个必须到处补的过滤条件。
--
-- 于是：菜单不想显示 → 用 visible=0（语义明确）；不想存在 → 删掉。
-- 权限想收回 → 在角色授权里取消勾选；不想存在 → 删掉。
-- 角色不想用 → 把用户从角色里摘掉；不想存在 → 删掉。
--
-- 【三】真删除之后，级联删除必须在同一个事务里手工做
--
-- 没有外键，所以数据库不会替我们清关联表。删除一个主体时必须显式清掉指向它的关系行，
-- 否则留下的是**孤儿关系行**：菜单已经不存在，但 sys_role_menu 里还挂着一行，
-- 下次做「角色菜单」查询时会 join 出空结果，前端表现为「勾选的菜单莫名其妙少了」。
--
-- 落点全部在 Service 的同一个 @Transactional 方法里：
--
--   sys_role          → sys_role_permission、sys_role_menu、sys_user_role
--   sys_permission    → sys_role_permission
--   sys_menu          → sys_role_menu（且必须先确认没有子菜单）
--   connection_definition → connection_observation，且要先停运行时再删
--
-- 唯一不做删除的用户：sys_user 目前没有删除入口（只有封号）。将来若要做注销，
-- 同样要在这里补上 sys_user_role 的清理。
