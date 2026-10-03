package com.loombot.plugin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("plugin_node")
@Getter
@Setter
public class PluginNode {

    @TableId private Long id;

    private Long pluginVersionId;
    private String nodeKey;

    /** EVENT / ACTION / NODE。 */
    private String nodeType;

    /** 非空表示事件或动作属于某个适配器类型、执行时必须绑定连接；NODE 为空。 */
    private String connectionType;

    private String name;
    private String description;
    private String inputSchema;
    private String outputSchema;
    private String sourceRef;

    /** 节点签名哈希：参数名、类型、默认值或返回字段变化时工作流需要重新保存。 */
    private String signatureHash;

    private Integer sort;
    private LocalDateTime createTime;
}
