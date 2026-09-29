package com.loom.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loom.workflow.domain.WorkflowVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowVersionMapper extends BaseMapper<WorkflowVersion> {}
