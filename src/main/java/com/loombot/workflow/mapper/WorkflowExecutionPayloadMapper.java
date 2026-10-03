package com.loombot.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loombot.workflow.domain.WorkflowExecutionPayload;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowExecutionPayloadMapper extends BaseMapper<WorkflowExecutionPayload> {}
