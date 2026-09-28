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
        String connectionType,
        Direction direction,
        JsonNode config,
        String endpointPath,
        long desiredRevision,
        boolean enabled,
        String configHash) {}
