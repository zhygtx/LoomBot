"""组装整份插件目录。"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from system.scanner.adapters import scan_adapter
from system.scanner.errors import ScanError
from system.scanner.manifest import read_manifest
from system.scanner.nodes import scan_entities, scan_workflow_nodes

PROTOCOL_VERSION = 1


def build_catalog(plugin_dir: Path) -> dict[str, Any]:
    """扫描一个插件包，产出适配器、工作流节点和实体。"""
    root = Path(plugin_dir).resolve()
    if not root.is_dir():
        raise ScanError(f"插件目录不存在: {root}")
    manifest = read_manifest(root)
    owners: dict[str, str] = {}

    def claim(node: dict[str, Any], owner: str) -> None:
        key = str(node.get("nodeKey") or "")
        if key in owners:
            raise ScanError(f"节点键重复: {key}（{owners[key]} 与 {owner}）")
        owners[key] = owner

    adapters: list[dict[str, Any]] = []
    for declaration in manifest.adapters:
        scanned = scan_adapter(root, declaration)
        for node in scanned["nodes"]:
            claim(node, f"{declaration.adapter_type} 事件")
        for node in scanned["actions"]:
            claim(node, f"{declaration.adapter_type} 动作")
        adapters.append(scanned)

    entities = scan_entities(root)
    nodes = scan_workflow_nodes(root, entities)
    for node in nodes:
        claim(node, "工作流节点")

    return {
        "protocolVersion": PROTOCOL_VERSION,
        "pluginKey": manifest.key,
        "pluginVersion": manifest.version,
        "pluginName": manifest.name,
        "pluginDescription": manifest.description,
        "pluginAuthor": manifest.author,
        "capabilities": list(manifest.capabilities),
        "adapters": adapters,
        "nodes": nodes,
        "entities": list(entities.values()),
    }
