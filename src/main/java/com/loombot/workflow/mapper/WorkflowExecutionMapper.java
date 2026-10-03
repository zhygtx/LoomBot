package com.loombot.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loombot.workflow.domain.WorkflowExecution;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowExecutionMapper extends BaseMapper<WorkflowExecution> {}
