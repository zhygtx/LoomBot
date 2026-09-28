package com.loom.runtime.plugin;

import java.util.List;
import java.util.Optional;

/**
 * 插件注册表的只读目录。
 *
 * <p>实现由 plugin 模块提供；connection / workflow 只依赖本接口，不直接依赖 plugin 实体。
 * 接口只表达“有哪些版本、每个版本声明了什么”，实现直接读取数据库，不维护内存快照。
 */
public interface PluginCatalog {

    List<AdapterConnectionTypeRuntime> adapterConnectionTypes();

    Optional<AdapterConnectionTypeRuntime> adapterConnectionType(
            long pluginVersionId, String connectionType);

    Optional<PluginVersionRuntime> adapterVersion(long pluginVersionId);
}
