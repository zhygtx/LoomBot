-- ============================================================================
--  V1 · RBAC 权限五表 + 动态菜单
-- ----------------------------------------------------------------------------
--  Flyway 迁移脚本 —— 应用启动时自动执行，执行记录写入 flyway_schema_history 表。
--
--  ⚠️ 本文件一旦执行过就不要修改（Flyway 会校验 checksum，改了会导致启动失败）。
--     需要变更表结构时，新增 V2__xxx.sql、V3__xxx.sql 递增。
--
--  注意：这里刻意不含 CREATE DATABASE / USE 语句 —— Flyway 已经连在目标库上，
--        建库是部署前的一次性动作，见 schema/00_create_database.sql。
--
--  表清单：
--    1. sys_user             用户表
--    2. sys_role             角色表
--    3. sys_permission       权限表（= 菜单表，目录/菜单/按钮/接口 四类合一）
--    4. sys_user_role        用户 - 角色 关联表
--    5. sys_role_permission  角色 - 权限 关联表
--
--  ★ 核心设计：菜单与权限同源
--      权限点和菜单是同一张表、同一套权限串。type=MENU 的行既决定前端菜单与路由，
--      其 perm 字段又同时是后端接口权限。这样「能看见」与「能调用」天然一致。
--
--  权限串约定：域:资源:操作，固定三段，例如 plugin:manage:add
--      支持分段通配：*:*:* 为超级权限，plugin:*:* 覆盖插件模块全部操作。
--      匹配逻辑（后端 PermissionEvaluator 实现）：
--          static boolean match(String granted, String required) {
--              String[] g = granted.split(":"), r = required.split(":");
--              if (g.length != r.length) return false;
--              for (int i = 0; i < g.length; i++)
--                  if (!"*".equals(g[i]) && !g[i].equals(r[i])) return false;
--              return true;
--          }
--
--  主键约定：BIGINT 雪花 ID，对应 application.yml 中
--           mybatis-plus.global-config.db-config.id-type=assign_id
--           所以不使用 AUTO_INCREMENT，种子数据显式指定 ID。
--
--  外键约定：刻意不加 FOREIGN KEY，关联完整性由应用层保证，
--           避免后续分库分表 / 数据迁移被外键约束卡住。
-- ============================================================================


-- ============================================================================
--  1. 用户表
--     不含头像 / 昵称：本项目不涉及社区与社交展示，用户名即标识。
-- ============================================================================
CREATE TABLE `sys_user` (
    `id`              BIGINT       NOT NULL                COMMENT '用户 ID（雪花）',
    `username`        VARCHAR(64)  NOT NULL                COMMENT '登录名',
    `password`        VARCHAR(255) NOT NULL                COMMENT '密码哈希（BCrypt，不存明文）',
    `email`           VARCHAR(128)     NULL                COMMENT '邮箱（找回密码 / 验证码）',
    `phone`           VARCHAR(32)      NULL                COMMENT '手机号',
    `status`          TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=正常，0=停用',
    `last_login_time` DATETIME         NULL                COMMENT '最后登录时间',
    `last_login_ip`   VARCHAR(64)      NULL                COMMENT '最后登录 IP',
    `remark`          VARCHAR(255)     NULL                COMMENT '备注',
    `deleted`         TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删，1=已删',
    `create_by`       BIGINT           NULL                COMMENT '创建人 ID',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT           NULL                COMMENT '更新人 ID',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_email` (`email`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

-- 注意：uk_username 是唯一索引，配合逻辑删除时「删除后的用户名仍被占用」。
--       若需要复用已删用户的用户名，可把 deleted 改成「删除时写入本行 id」并建联合唯一键。


-- ============================================================================
--  2. 角色表
-- ============================================================================
CREATE TABLE `sys_role` (
    `id`          BIGINT       NOT NULL                COMMENT '角色 ID（雪花）',
    `code`        VARCHAR(64)  NOT NULL                COMMENT '角色标识：USER / ADMIN / OWNER',
    `name`        VARCHAR(64)  NOT NULL                COMMENT '角色名称',
    `sort`        INT          NOT NULL DEFAULT 0      COMMENT '显示顺序，越小越靠前',
    `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=正常，0=停用',
    `builtin`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否内置：1=内置角色，不允许删除',
    `remark`      VARCHAR(255)     NULL                COMMENT '备注',
    `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删，1=已删',
    `create_by`   BIGINT           NULL                COMMENT '创建人 ID',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   BIGINT           NULL                COMMENT '更新人 ID',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_sort` (`sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色表';


-- ============================================================================
--  3. 权限表（同时是菜单表）
--
--     type 取值：
--       CATALOG  目录   —— 前端一级分组，通常 perm 为空
--       MENU     菜单   —— 前端可访问页面；perm 同时是后端列表接口的权限
--       BUTTON   按钮   —— 页面内操作点；perm 同时是后端写接口的权限
--       API      接口   —— 不需要前端入口的纯后端权限
--
--     MENU / BUTTON 行的字段分工：
--       path / component / redirect / icon / visible / keep_alive / sort → 前端路由与菜单
--       perm                                                            → 前后端共用的权限串
-- ============================================================================
CREATE TABLE `sys_permission` (
    `id`          BIGINT       NOT NULL                COMMENT '权限 ID（雪花）',
    `parent_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '父级 ID，0=顶级',
    `name`        VARCHAR(64)  NOT NULL                COMMENT '权限 / 菜单名称',
    `type`        VARCHAR(16)  NOT NULL                COMMENT '类型：CATALOG / MENU / BUTTON / API',
    `perm`        VARCHAR(128)     NULL                COMMENT '权限串，域:资源:操作，如 plugin:manage:add',
    `path`        VARCHAR(255)     NULL                COMMENT '前端路由地址，如 /plugin/manage',
    `component`   VARCHAR(255)     NULL                COMMENT '前端组件路径，如 plugin/manage/index',
    `redirect`    VARCHAR(255)     NULL                COMMENT '目录默认跳转地址',
    `icon`        VARCHAR(64)      NULL                COMMENT '图标',
    `sort`        INT          NOT NULL DEFAULT 0      COMMENT '同级显示顺序',
    `visible`     TINYINT      NOT NULL DEFAULT 1      COMMENT '是否在菜单显示：1=显示，0=隐藏（隐藏但可访问）',
    `keep_alive`  TINYINT      NOT NULL DEFAULT 0      COMMENT '前端是否缓存该页面：1=缓存',
    `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=正常，0=停用',
    `remark`      VARCHAR(255)     NULL                COMMENT '备注',
    `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删，1=已删',
    `create_by`   BIGINT           NULL                COMMENT '创建人 ID',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   BIGINT           NULL                COMMENT '更新人 ID',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_perm` (`perm`),
    KEY `idx_parent` (`parent_id`),
    KEY `idx_type` (`type`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限表（同时是菜单表）';

-- 说明：uk_perm 建在可空的 perm 上。MySQL 唯一索引允许多个 NULL，
--       所以目录类（perm 为空）的记录可以有多条，不会互相冲突。已实测验证。


-- ============================================================================
--  4. 用户 - 角色 关联表
--     采用联合主键，没有代理键。
--     ⚠️ 因此 MyBatis-Plus 的 BaseMapper 无法直接用于本表（它要求单一 @TableId），
--        需在 Mapper XML 里写自定义方法：
--          deleteByUserId(Long userId)
--          batchInsert(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds)
-- ============================================================================
CREATE TABLE `sys_user_role` (
    `user_id`     BIGINT   NOT NULL COMMENT '用户 ID',
    `role_id`     BIGINT   NOT NULL COMMENT '角色 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`user_id`, `role_id`),
    KEY `idx_role` (`role_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户-角色关联表';


-- ============================================================================
--  5. 角色 - 权限 关联表
-- ============================================================================
CREATE TABLE `sys_role_permission` (
    `role_id`       BIGINT   NOT NULL COMMENT '角色 ID',
    `permission_id` BIGINT   NOT NULL COMMENT '权限 ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`role_id`, `permission_id`),
    KEY `idx_permission` (`permission_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '角色-权限关联表';


-- ============================================================================
--  种子数据
-- ============================================================================

-- 三个内置角色
INSERT INTO `sys_role` (`id`, `code`, `name`, `sort`, `status`, `builtin`, `remark`) VALUES
    (1, 'USER',  '用户',   30, 1, 1, '默认角色，注册即拥有'),
    (2, 'ADMIN', '管理员', 20, 1, 1, '日常运营管理'),
    (3, 'OWNER', '站长',   10, 1, 1, '最高权限，建议仅一人持有');

-- 超级权限点：持有 *:*:* 即放行全部鉴权
INSERT INTO `sys_permission` (`id`, `parent_id`, `name`, `type`, `perm`, `sort`, `visible`, `remark`) VALUES
    (1, 0, '超级权限', 'API', '*:*:*', 0, 0, '通配全部权限串，仅授予站长');

-- 站长只绑这一条，不需要往关联表里堆全量权限。
-- 好处：以后每新增一个权限点，站长自动拥有，无需回来补关联行。
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
    (3, 1);

-- 其余权限点（菜单 / 按钮）暂不插种子数据：具体菜单应随功能模块落地时再添加。
-- 建议后端加一条启动自检：扫描所有 @PreAuthorize 里的权限串，
-- 与 sys_permission 表比对，缺失则启动失败并列出清单，防止代码与数据漂移。
