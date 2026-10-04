package com.loombot.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 工作流里引用了「已变更 / 已删除」的插件节点时留的提醒。 */
@TableName("workflow_node_alert")
@Getter
@Setter
public class WorkflowNodeAlert {

    /** 节点还在，但参数或返回值变了。 */
    public static final String REASON_CHANGED = "CHANGED";

    /** 节点已经被删掉。 */
    public static final String REASON_REMOVED = "REMOVED";

    @TableId private Long id;

    private Long workflowId;

    /** 画布上的节点 id（定义里的 node.id）；同一个 node_key 可以在画布上出现多次。 */
    private String nodeId;

    private String nodeKey;
    private Long pluginVersionId;
    private String reason;
    private String detail;
    private LocalDateTime createTime;

    /**
     * 以下都是**从插件目录抄过来的身份快照**。
     *
     * <p>插件目录改成可变镜像之后，被删除的版本/节点/连接类型会直接从库里消失。 那时这条记录是唯一还能解释"原来是什么、为什么失效"的地方，所以身份信息必须自带， 不能再依赖 join
     * 插件目录表。
     */
    private Long workflowVersionId;

    private String nodeName;
    private String nodeType;
    private String pluginKey;
    private String pluginVersion;
    private Long connectionId;
    private String connectionName;
    private LocalDateTime detectedAt;
}
