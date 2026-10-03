package com.loombot.connection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loombot.connection.domain.WsConnection;
import org.apache.ibatis.annotations.Mapper;

/**
 * WS 连接持久层。
 *
 * <p>本表是普通单主键结构，{@code BaseMapper} 已经够用， 不需要像联合主键的关联表那样写自定义 XML。
 */
@Mapper
public interface WsConnectionMapper extends BaseMapper<WsConnection> {}
