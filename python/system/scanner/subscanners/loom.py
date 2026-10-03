"""内置子扫描器：Loom 自己的插件库格式。

索引是 `index.json`：

```json
{
  "schemaVersion": 1,
  "plugins": [
    { "key": "text-tools", "namespace": "loom",
      "versions": [ { "version": "1.0.0", "path": "text-tools", "publishedTime": "..." } ] }
  ]
}
```

每个 `path` 指向仓库里的一个插件目录，目录里有 `plugin.toml`，代码用 `@event` / `@action` / `@node`
装饰器声明可调用单元。节点目录由 `system.scanner.catalog` 扫描。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable

from system.scanner.catalog import build_catalog
from system.scanner.errors import ScanError
from system.scanner.loader import collect_workflow_nodes, iter_python_files

INDEX_NAME = "index.json"
NODES_DIR = "nodes"


def list_plugins(repo_root: Path) -> list[dict[str, Any]]:
    """读 Loom 的 index.json，归一成主扫描器要的插件列表。"""
    index_path = Path(repo_root) / INDEX_NAME
    if not index_path.is_file():
        raise ScanError(f"插件库缺少 {INDEX_NAME}：{index_path}")
    try:
        data = json.loads(index_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        raise ScanError(f"读取 {INDEX_NAME} 失败：{exc}") from exc
    if not isinstance(data, dict):
        raise ScanError(f"{INDEX_NAME} 必须是 JSON 对象")

    plugins: list[dict[str, Any]] = []
    for entry in data.get("plugins") or []:
        if not isinstance(entry, dict):
            continue
        key = str(entry.get("key") or "").strip()
        if not key:
            raise ScanError("index.json 存在缺少 key 的插件")
        versions: list[dict[str, str]] = []
        for item in entry.get("versions") or []:
            if not isinstance(item, dict):
                continue
            path = str(item.get("path") or "").strip()
            if not path:
                continue
            versions.append(
                {
                    "version": str(item.get("version") or "").strip(),
                    "path": path,
                    "publishedTime": str(item.get("publishedTime") or "").strip(),
                }
            )
        if not versions:
            continue
        plugins.append(
            {
                "key": key,
                "namespace": str(entry.get("namespace") or "").strip(),
                "versions": versions,
            }
        )
    return plugins


def scan_plugin(plugin_dir: Path, entry: dict[str, Any]) -> dict[str, Any]:
    """扫描一个 Loom 插件目录，产出节点目录。`entry` 用不到，保留是为了契约统一。"""
    return build_catalog(Path(plugin_dir))


def load_nodes(plugin_dir: Path) -> dict[str, Callable[..., Any]]:
    """导入 `nodes/*.py`，把节点键映射到 `@node` 装饰过的函数。

    和目录期同源：目录期也是 import 这些文件再读装饰器注册表，所以键和签名天然一致。
    """
    root = Path(plugin_dir)
    nodes: dict[str, Callable[..., Any]] = {}
    for path in iter_python_files(root / NODES_DIR):
        for spec in collect_workflow_nodes(path, root, len(nodes)):
            nodes[spec.key] = spec.func
    return nodes
