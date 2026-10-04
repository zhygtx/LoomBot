-- 插件目录改成"可变镜像"：库里没有的版本/节点/连接类型直接删除。
-- 删除之后，引用过它们的工作流不再有任何地方能解释"为什么失效"，
-- 所以失效信息必须落到工作流自己身上。本迁移补两件事：
--   1) 工作流自身的可用性状态，与用户开关 enabled 分开；
--   2) 节点失效记录补齐身份快照，目录行删掉后仍然可读。

-- ============================================================
-- 工作流可用性（系统判定），与 enabled（用户开关）分离
-- ============================================================
ALTER TABLE workflow_info
    ADD COLUMN availability VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE'
        COMMENT 'AVAILABLE=可用 / UNAVAILABLE=节点已失效',
    ADD COLUMN availability_checked_at DATETIME NULL
        COMMENT '最后一次可用性检查时间';

-- ============================================================
-- 节点失效记录补齐身份快照
-- ============================================================
-- 这些字段是从插件目录"抄"过来的快照。插件目录行被物理删除后，
-- 前端只能靠它们把失效原因讲清楚（原来是什么节点、哪个插件、哪个连接）。
ALTER TABLE workflow_node_alert
    ADD COLUMN workflow_version_id BIGINT NULL
        COMMENT '所属工作流版本（记录失效发生在哪一版）',
    ADD COLUMN node_name VARCHAR(128) NULL
        COMMENT '节点名称快照',
    ADD COLUMN node_type VARCHAR(16) NULL
        COMMENT '节点类型快照：EVENT / ACTION / NODE',
    ADD COLUMN plugin_key VARCHAR(128) NULL
        COMMENT '插件键快照，如 loombot.qq-official-adapter',
    ADD COLUMN plugin_version VARCHAR(64) NULL
        COMMENT '插件版本号快照',
    ADD COLUMN connection_id BIGINT NULL
        COMMENT '节点引用的连接',
    ADD COLUMN connection_name VARCHAR(128) NULL
        COMMENT '连接名快照（连接行被删除后仍可读）',
    ADD COLUMN detected_at DATETIME NULL
        COMMENT '发现时间';

-- 反向查询：按节点找受影响的工作流（插件对账时用）
CREATE INDEX idx_wfa_node ON workflow_node_alert (plugin_key, node_key);
-- 按工作流版本查（画布标注时用）
CREATE INDEX idx_wfa_workflow_version ON workflow_node_alert (workflow_version_id);
