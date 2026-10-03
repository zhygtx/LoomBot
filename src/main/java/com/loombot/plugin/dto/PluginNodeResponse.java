package com.loombot.plugin.dto;

/** 插件版本对外暴露的节点目录。 */
public record PluginNodeResponse(
        String nodeKey,
        String nodeType,
        String connectionType,
        String name,
        String description,
        String sourceRef,
        Integer sort) {}
