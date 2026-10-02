package com.loom.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loom.workflow.domain.WorkflowVersion;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowVersionMapper extends BaseMapper<WorkflowVersion> {

    /**
     * 删除「非当前版本、已过保留期、且没有任何执行记录引用」的定义版本。
     *
     * <p>执行记录的 definition_version 存的是 version_no，所以按 (workflow_id, version_no) 关联。 多表 DELETE 在
     * MySQL 不支持 LIMIT，这里靠 join 条件天然收敛（版本只在每次保存时新增一条）。
     */
    @Delete(
            """
            DELETE v FROM workflow_version v
            JOIN workflow_info i ON i.id = v.workflow_id
            LEFT JOIN workflow_execution e
                   ON e.workflow_id = v.workflow_id AND e.definition_version = v.version_no
            WHERE (i.current_version_id IS NULL OR v.id <> i.current_version_id)
              AND v.create_time < #{cutoff}
              AND e.id IS NULL
            """)
    int deleteUnreferencedBefore(@Param("cutoff") LocalDateTime cutoff);
}
