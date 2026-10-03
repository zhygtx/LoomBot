package com.loombot.workflow.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 工作流执行小时汇总（workflow_execution_hourly）。
 *
 * <p>原始执行日志只留 N 天，这里留得久得多，用来画长期趋势。
 */
@Mapper
public interface WorkflowExecutionHourlyMapper {

    /**
     * 把一批即将删除的执行记录聚合进小时汇总。
     *
     * <p>计数是**累加**的（`ON DUPLICATE KEY UPDATE` 用 `+`），所以调用方必须在同一个事务里紧跟着删掉这批
     * 执行记录：只要删成功，同一批就不可能被聚合第二次。反过来，先聚合后删中间失败会重复计数， 这就是要求同事务的原因。
     *
     * <p>存 `total_duration_ms` 而不是平均值：平均值没法增量合并，先存和，读的时候再除。
     */
    @Insert(
            """
            <script>
            INSERT INTO workflow_execution_hourly
                (biz_hour, owner_user_id, workflow_id, connection_id, trigger_type,
                 total_count, success_count, timeout_count, failed_count, total_duration_ms)
            SELECT DATE_FORMAT(start_time, '%Y-%m-%d %H:00:00'),
                   owner_user_id,
                   workflow_id,
                   COALESCE(connection_id, 0),
                   COALESCE(trigger_type, ''),
                   COUNT(*),
                   SUM(status = 'SUCCESS'),
                   SUM(status = 'TIMEOUT'),
                   SUM(status = 'FAILED'),
                   COALESCE(SUM(duration_ms), 0)
              FROM workflow_execution
             WHERE id IN
             <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
             GROUP BY 1, 2, 3, 4, 5
            ON DUPLICATE KEY UPDATE
                total_count = total_count + VALUES(total_count),
                success_count = success_count + VALUES(success_count),
                timeout_count = timeout_count + VALUES(timeout_count),
                failed_count = failed_count + VALUES(failed_count),
                total_duration_ms = total_duration_ms + VALUES(total_duration_ms)
            </script>
            """)
    int rollupByIds(@Param("ids") List<Long> ids);

    /** 删工作流时把它的汇总一起带走，否则会留下永远没人查的孤儿行。 */
    @Delete("DELETE FROM workflow_execution_hourly WHERE workflow_id = #{workflowId}")
    int deleteByWorkflowId(@Param("workflowId") Long workflowId);

    @Delete("DELETE FROM workflow_execution_hourly WHERE biz_hour < #{cutoffHour}")
    int deleteBefore(@Param("cutoffHour") LocalDateTime cutoffHour);
}
