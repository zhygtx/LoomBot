package com.loombot.plugin.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PluginVersionResponse(
        Long id,
        String version,
        String sourceCommitHash,
        String entryPoint,
        LocalDateTime syncedTime,
        List<AdapterTypeResponse> adapterTypes,
        List<PluginNodeResponse> nodes) {}
