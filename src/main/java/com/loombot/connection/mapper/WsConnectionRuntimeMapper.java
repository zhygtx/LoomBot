package com.loombot.connection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loombot.connection.domain.WsConnectionRuntime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WsConnectionRuntimeMapper extends BaseMapper<WsConnectionRuntime> {

    @Insert(
            """
            <script>
            INSERT INTO connection_observation
                (connection_id, instance_id, state, runtime_reachable, failure_reason,
                 observed_revision, last_frame_at, last_synced_at)
            VALUES
            <foreach collection="statuses" item="item" separator=",">
                (#{item.connectionId}, #{item.instanceId}, #{item.state},
                 #{item.runtimeReachable}, #{item.failureReason},
                 #{item.observedRevision}, #{item.lastFrameAt}, #{item.lastSyncedAt})
            </foreach>
            ON DUPLICATE KEY UPDATE
                   instance_id = VALUES(instance_id),
                   state = VALUES(state),
                   runtime_reachable = VALUES(runtime_reachable),
                   failure_reason = VALUES(failure_reason),
                   observed_revision = VALUES(observed_revision),
                   last_frame_at = VALUES(last_frame_at),
                   last_synced_at = VALUES(last_synced_at)
            </script>
            """)
    int upsertBatch(@Param("statuses") List<WsConnectionRuntime> statuses);

    /**
     * 清理连接已删除但状态投影仍存在的孤儿行。
     *
     * <p>项目不使用数据库外键，删除连接和状态同步并发时可能短暂产生孤儿；每次同步周期清理一次即可。
     */
    @Delete(
            "DELETE r FROM connection_observation r "
                    + "LEFT JOIN connection_definition c ON c.id = r.connection_id "
                    + "WHERE c.id IS NULL")
    int deleteOrphans();
}
