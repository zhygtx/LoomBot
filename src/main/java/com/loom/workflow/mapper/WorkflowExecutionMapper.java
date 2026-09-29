package com.loom.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loom.workflow.domain.WorkflowExecution;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowExecutionMapper extends BaseMapper<WorkflowExecution> {}
