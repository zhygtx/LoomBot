"""适配器插件扫描：事件与动作。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from plugin_catalog.errors import ScanError
from plugin_catalog.loader import collect_adapter_nodes, iter_python_files
from plugin_catalog.manifest import AdapterDecl


def _direction(plugin_dir: Path, adapter: AdapterDecl) -> str:
    """连接方向来自配置模式的 x-direction。"""
    if not adapter.connection_schema:
        raise ScanError(f"适配器 {adapter.adapter_type} 缺少 connection_schema")
    path = plugin_dir / adapter.connection_schema
    if not path.is_file():
        raise ScanError(f"适配器 {adapter.adapter_type} 的配置模式不存在: {adapter.connection_schema}")
    try:
        schema = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise ScanError(f"配置模式解析失败 {adapter.connection_schema}: {exc}") from exc
    if not isinstance(schema, dict):
        raise ScanError(f"配置模式必须是 JSON 对象: {adapter.connection_schema}")
    direction = str(schema.get("x-direction") or "").strip().upper()
    if direction not in {"FORWARD", "REVERSE"}:
        raise ScanError(
            f"配置模式 {adapter.connection_schema} 的 x-direction 必须是 FORWARD 或 REVERSE"
        )
    return direction


def _scan_directory(
    plugin_dir: Path,
    directory: Path,
    adapter: AdapterDecl,
    expected_kind: str,
) -> list[Any]:
    specs: list[Any] = []
    for path in iter_python_files(directory):
        for spec in collect_adapter_nodes(
            path, plugin_dir, adapter.adapter_type, len(specs)
        ):
            if spec.kind != expected_kind:
                raise ScanError(
                    f"{spec.source_ref or spec.key} 声明为 {spec.kind}，"
                    f"但放在 {directory.name} 目录里"
                )
            specs.append(spec)
    return specs


def scan_adapter(plugin_dir: Path, adapter: AdapterDecl) -> dict[str, Any]:
    """扫描一个适配器类型的事件和动作。"""
    nodes, actions = scan_adapter_specs(plugin_dir, adapter)
    return {
        "adapterType": adapter.adapter_type,
        "entry": adapter.entry,
        "connectionSchema": adapter.connection_schema,
        "protocolVersion": adapter.protocol_version,
        "schemaVersion": adapter.schema_version,
        "direction": _direction(plugin_dir, adapter),
        "eventsDir": adapter.events_dir,
        "actionsDir": adapter.actions_dir,
        "nodes": [spec.as_dict() for spec in nodes],
        "actions": [spec.as_dict() for spec in actions],
    }


def scan_adapter_specs(plugin_dir: Path, adapter: AdapterDecl) -> tuple[list[Any], list[Any]]:
    """扫描并返回带函数对象的声明，供工作进程建立注册表。"""
    nodes = _scan_directory(plugin_dir, plugin_dir / adapter.events_dir, adapter, "EVENT")
    actions = _scan_directory(plugin_dir, plugin_dir / adapter.actions_dir, adapter, "ACTION")
    return nodes, actions
