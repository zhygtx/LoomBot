package com.loombot.plugin.controller;

import com.loombot.common.api.Result;
import com.loombot.plugin.dto.PluginResponse;
import com.loombot.plugin.service.PluginQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 插件目录。发现完全自动，目录读取直接命中运行时预载的内存快照。 */
@RestController
@RequestMapping("/api/plugin")
public class PluginController {

    private final PluginQueryService queryService;

    public PluginController(PluginQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public Result<List<PluginResponse>> list() {
        return Result.success(queryService.list());
    }
}
