package com.loombot.plugin.controller;

import com.loombot.common.api.Result;
import com.loombot.plugin.dto.PluginRepoRequest;
import com.loombot.plugin.dto.PluginRepoResponse;
import com.loombot.plugin.service.PluginRepositoryAdminService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 插件库管理。
 *
 * <p>写操作之后都会异步触发一次同步，站长不用等下一个 30 秒周期。同步是后台跑的， 进度看列表里的 lastScanTime / lastError。
 *
 * <p>上传配套文件（`scanner.py` 等）等于在服务器上执行代码，所以单独挂在 `plugin:repo:manage` 上， 和只读的 `plugin:repo:list` 分开授权。
 */
@RestController
@RequestMapping("/api/plugin/repository")
public class PluginRepositoryController {

    private final PluginRepositoryAdminService service;

    public PluginRepositoryController(PluginRepositoryAdminService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@permission.has(authentication, 'plugin:repo:list')")
    public Result<List<PluginRepoResponse>> list() {
        return Result.success(service.list());
    }

    @PutMapping("/{key}")
    @PreAuthorize("@permission.has(authentication, 'plugin:repo:manage')")
    public Result<PluginRepoResponse> save(
            @PathVariable String key, @RequestBody PluginRepoRequest request) {
        PluginRepoResponse response = service.save(key, request);
        service.triggerSync();
        return Result.success(response);
    }

    /** 上传库文件夹的配套文件：一个 zip，或单个 `.py`（如 `scanner.py`）。 */
    @PostMapping("/{key}/files")
    @PreAuthorize("@permission.has(authentication, 'plugin:repo:manage')")
    public Result<PluginRepoResponse> upload(
            @PathVariable String key, @RequestParam("file") MultipartFile file) {
        PluginRepoResponse response = service.upload(key, file);
        service.triggerSync();
        return Result.success(response);
    }

    @DeleteMapping("/{key}")
    @PreAuthorize("@permission.has(authentication, 'plugin:repo:manage')")
    public Result<Boolean> delete(@PathVariable String key) {
        service.delete(key);
        service.triggerSync();
        return Result.success(true);
    }

    /** 手动触发一次同步；正在同步时会直接跳过，不会排两次。 */
    @PostMapping("/sync")
    @PreAuthorize("@permission.has(authentication, 'plugin:repo:manage')")
    public Result<Boolean> sync() {
        service.triggerSync();
        return Result.success(true);
    }
}
