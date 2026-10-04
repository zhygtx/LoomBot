package com.loombot.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 工作流稳定身份：名称、启用状态与当前版本。 */
@TableName("workflow_info")
@Getter
@Setter
public class WorkflowInfo {

    /** 可用：节点引用都能在当前插件目录里解析出来。 */
    public static final String AVAILABILITY_AVAILABLE = "AVAILABLE";

    /** 已失效：有节点引用的插件节点被删除或契约变更，处理完保存新版本才会回到可用。 */
    public static final String AVAILABILITY_UNAVAILABLE = "UNAVAILABLE";

    @TableId private Long id;

    private String name;
    private String description;
    private Integer enabled;

    /**
     * 系统判定的可用性，和用户开关 {@code enabled} 是两件事。
     *
     * <p>分开的原因：靠把 {@code enabled} 置 0 表示失效，会把"我手动关的"和"系统判不可用的" 混成同一个状态，用户既看不出区别，也没法在修好之后恢复原意。
     */
    private String availability;

    private LocalDateTime availabilityCheckedAt;

    private Long currentVersionId;
    private Long ownerUserId;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;

    /**
     * 列表页角标：这条工作流有没有节点失效提醒。
     *
     * <p>不是表字段，由查询时补上。放实体上而不是另建 DTO，是因为列表接口本来就返回这个实体， 多一个字段比多一层映射划算。
     */
    @TableField(exist = false)
    private Boolean hasAlert;
}
