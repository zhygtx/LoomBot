package com.loom.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loom.plugin.domain.Plugin;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PluginMapper extends BaseMapper<Plugin> {}
