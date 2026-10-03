package com.loom.adapter;

import com.loom.connection.domain.Direction;
import tools.jackson.databind.JsonNode;

/** 控制面发送给监管器的一条不可变连接期望。 */
public record AdapterConnectionCommand(
        long connectionId,
        long pluginVersionId,
        String pluginKey,
        String pluginVersion,
        String pluginPath,
        String entryPoint,
        /** 插件私有 venv 的解释器路径；为空表示这个版本没有 requirements.txt，用宿主 Python。 */
        String pythonPath,
        /** 制品哈希：变了说明插件代码被原地覆盖，监管器要重启工作进程。 */
        String artifactSha256,
        String connectionType,
        Direction direction,
        JsonNode config,
        String endpointPath,
        long desiredRevision,
        boolean enabled,
        String configHash) {}
