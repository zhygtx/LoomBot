package com.loom.plugin.dto;

/** 插件版本对外暴露的工作流节点目录。 */
public record PluginNodeResponse(
        String nodeKey, String nodeType, String name, String description) {}
