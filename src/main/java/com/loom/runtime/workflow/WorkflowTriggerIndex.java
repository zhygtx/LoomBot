package com.loom.runtime.workflow;

import java.util.List;

/** Adapter 查询某连接节点命中了哪些不可变工作流版本。 */
public interface WorkflowTriggerIndex {

    List<Long> findWorkflowVersionIds(long connectionId, String connectionType, String nodeKey);
}
