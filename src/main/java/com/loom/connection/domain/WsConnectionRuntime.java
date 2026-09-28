package com.loom.connection.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Adapter 连接状态在 MySQL 中的最近一次同步快照。 */
@TableName("connection_observation")
@Getter
@Setter
public class WsConnectionRuntime {

    @TableId(value = "connection_id", type = IdType.INPUT)
    private Long connectionId;

    private String instanceId;

    private String state;

    /** 最近一次状态同步时 Adapter 控制 API 是否可达。 */
    private Integer runtimeReachable;

    private String failureReason;

    private Long observedRevision;

    private Long lastFrameAt;

    private LocalDateTime lastSyncedAt;
}
