"""按插件版本加载工作流节点。"""

from __future__ import annotations

import asyncio
import inspect
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from system.scanner.loader import collect_workflow_nodes, iter_python_files

log = logging.getLogger("workflow-plugins")


@dataclass
class PluginRuntime:
    """一个插件版本的节点运行时。"""

    plugin_version_id: int
    plugin_key: str
    plugin_dir: Path
    nodes: dict[str, Any]

    async def invoke(self, node_key: str, ctx: Any, args: list[Any], kwargs: dict[str, Any]) -> Any:
        spec = self.nodes.get(node_key)
        if spec is None:
            raise KeyError(f"插件版本 {self.plugin_version_id} 没有节点 {node_key}")
        func = spec.func
        # 同步函数放进线程池，阻塞代码不会卡住事件循环。
        if inspect.iscoroutinefunction(func):
            return await func(ctx, *args, **kwargs)
        return await asyncio.to_thread(func, ctx, *args, **kwargs)


class PluginRegistry:
    """按 pluginVersionId 缓存插件节点运行时。"""

    def __init__(self) -> None:
        self._runtimes: dict[int, PluginRuntime] = {}

    def get(self, plugin_version_id: int, install_path: str, plugin_key: str) -> PluginRuntime:
        runtime = self._runtimes.get(plugin_version_id)
        if runtime is not None:
            return runtime
        plugin_dir = Path(install_path).resolve()
        nodes: dict[str, Any] = {}
        for path in iter_python_files(plugin_dir / "nodes"):
            for spec in collect_workflow_nodes(path, plugin_dir, len(nodes)):
                nodes[spec.key] = spec
        runtime = PluginRuntime(plugin_version_id, plugin_key, plugin_dir, nodes)
        self._runtimes[plugin_version_id] = runtime
        log.info(
            "插件节点运行时已加载: version=%s key=%s nodes=%s",
            plugin_version_id,
            plugin_key,
            sorted(nodes),
        )
        return runtime
