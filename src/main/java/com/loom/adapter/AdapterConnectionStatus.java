package com.loom.adapter;

/** Python Adapter 返回的实际连接状态。 */
public record AdapterConnectionStatus(
        long connectionId,
        String instanceId,
        long pluginVersionId,
        String connectionType,
        long observedRevision,
        String state,
        String lastError,
        long lastFrameAt) {}
