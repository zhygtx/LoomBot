"""读取 plugin.toml。"""

from __future__ import annotations

import tomllib
from dataclasses import dataclass
from pathlib import Path

from plugin_catalog.errors import ScanError

DEFAULT_EVENTS_DIR = "events"
DEFAULT_ACTIONS_DIR = "actions"


@dataclass(frozen=True)
class AdapterDecl:
    """一个适配器类型的声明。"""

    adapter_type: str
    entry: str
    connection_schema: str
    protocol_version: str
    schema_version: str
    events_dir: str
    actions_dir: str


@dataclass(frozen=True)
class Manifest:
    """插件清单。"""

    key: str
    name: str
    version: str
    description: str
    author: str
    capabilities: tuple[str, ...]
    adapters: tuple[AdapterDecl, ...]
    path: Path


def _text(source: dict, key: str) -> str:
    value = source.get(key)
    return "" if value is None else str(value).strip()


def read_manifest(plugin_dir: Path) -> Manifest:
    """读取并校验插件清单。缺少关键字段直接失败。"""
    path = plugin_dir / "plugin.toml"
    if not path.is_file():
        raise ScanError(f"缺少 plugin.toml: {path}")
    try:
        with path.open("rb") as stream:
            toml = tomllib.load(stream)
    except tomllib.TOMLDecodeError as exc:
        raise ScanError(f"plugin.toml 解析失败: {exc}") from exc

    plugin = toml.get("plugin")
    if not isinstance(plugin, dict):
        raise ScanError("plugin.toml 缺少 [plugin] 段")
    key = _text(plugin, "key")
    version = _text(plugin, "version")
    if not key:
        raise ScanError("plugin.toml 缺少 plugin.key")
    if not version:
        raise ScanError("plugin.toml 缺少 plugin.version")

    capabilities: list[str] = []
    raw_capabilities = plugin.get("capabilities")
    if isinstance(raw_capabilities, list):
        for item in raw_capabilities:
            value = str(item).strip()
            if value:
                capabilities.append(value)

    adapters: list[AdapterDecl] = []
    raw_adapters = toml.get("adapters")
    if raw_adapters is not None and not isinstance(raw_adapters, list):
        raise ScanError("plugin.toml 的 [[adapters]] 必须是表数组")
    for table in raw_adapters or []:
        if not isinstance(table, dict):
            raise ScanError("plugin.toml 的 [[adapters]] 条目必须是表")
        adapter_type = _text(table, "type") or _text(table, "connection_type")
        if not adapter_type:
            raise ScanError("[[adapters]] 缺少 type")
        adapters.append(
            AdapterDecl(
                adapter_type=adapter_type,
                entry=_text(table, "entry") or "main.py",
                connection_schema=_text(table, "connection_schema"),
                protocol_version=_text(table, "protocol_version") or "1",
                schema_version=_text(table, "schema_version") or "1",
                events_dir=_text(table, "events_dir") or DEFAULT_EVENTS_DIR,
                actions_dir=_text(table, "actions_dir") or DEFAULT_ACTIONS_DIR,
            )
        )

    seen: set[str] = set()
    for adapter in adapters:
        if adapter.adapter_type in seen:
            raise ScanError(f"重复声明的适配器类型: {adapter.adapter_type}")
        seen.add(adapter.adapter_type)

    return Manifest(
        key=key,
        name=_text(plugin, "name") or key,
        version=version,
        description=_text(plugin, "description"),
        author=_text(plugin, "author"),
        capabilities=tuple(capabilities),
        adapters=tuple(adapters),
        path=plugin_dir,
    )
