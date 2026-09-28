package com.loom.plugin.dto;

import java.util.List;

public record PluginResponse(
        Long id,
        String pluginKey,
        String name,
        String description,
        String homepage,
        String author,
        List<PluginVersionResponse> versions) {}
