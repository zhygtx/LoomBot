package com.loombot.connection.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 连接类型描述 —— 来自 MySQL 中的插件注册表。
 *
 * <p>这个读模型只回答“用户可以选择什么插件版本、配置哪些字段”。它不表示 Adapter 当前是否已经 加载该版本；运行时状态在连接创建后由 status 接口单独返回。这样连接测试台在
 * Adapter 启动较慢 或临时不可用时，仍然可以先展示配置表单。
 *
 * @param pluginId 插件稳定身份
 * @param pluginVersionId 具体插件版本，连接创建后不可修改
 * @param pluginKey 插件包标识
 * @param pluginName 插件展示名
 * @param pluginVersion 插件 SemVer
 * @param type 类型标识，由适配器声明，创建连接时按它匹配
 * @param displayName 展示给用户的名称
 * @param direction 连接方向，决定接入地址归谁管、以及要由谁来发起
 * @param configSchema 配置表单 schema（JSON Schema + {@code x-} 扩展），Java 不解释其含义
 * @param capabilities 适配器声明的能力标记（如事件类型清单），供前端提示
 * @param adapterName 声明该类型的适配器进程名，排查时用
 */
public record ConnectionTypeDescriptor(
        Long pluginId,
        Long pluginVersionId,
        String pluginKey,
        String pluginName,
        String pluginVersion,
        String type,
        String displayName,
        Direction direction,
        JsonNode configSchema,
        List<String> capabilities,
        String adapterName) {}
