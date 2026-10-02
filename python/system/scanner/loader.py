"""插件模块加载与声明收集。"""

from __future__ import annotations

import hashlib
import importlib.util
import sys
from pathlib import Path
from types import ModuleType
from typing import Any

import loom_adapter
import loom_node
from system.scanner.errors import ScanError


def repo_root() -> Path:
    """Python 侧的根目录（`python/`），也是 `loom_adapter` / `loom_node` 所在目录。

    本文件在 `python/system/scanner/`，所以要往上三层；少一层会把 `python/system` 塞进 sys.path，
    那层没有 SDK，还平白多出一个可能遮蔽标准库的搜索路径。
    """
    return Path(__file__).resolve().parents[2]


def ensure_repo_on_path() -> None:
    root = str(repo_root())
    if root not in sys.path:
        sys.path.insert(0, root)


def ensure_plugin_on_path(plugin_dir: Path) -> None:
    """把插件目录放进 sys.path，让插件内的 lib/、entities/ 可以直接导入。"""
    root = str(plugin_dir.resolve())
    if root not in sys.path:
        sys.path.insert(0, root)


def iter_python_files(directory: Path) -> list[Path]:
    """按路径字典序返回待扫描文件，跳过 __pycache__、__init__.py 和下划线开头的内容。"""
    if not directory.is_dir():
        return []
    result: list[Path] = []
    for path in sorted(directory.rglob("*.py")):
        relative = path.relative_to(directory)
        if any(part == "__pycache__" or part.startswith("_") for part in relative.parts[:-1]):
            continue
        if relative.name.startswith("_") or relative.name == "__init__.py":
            continue
        result.append(path)
    return result


def load_module(path: Path, plugin_dir: Path) -> tuple[ModuleType, str]:
    """按文件路径加载插件模块，返回模块与相对插件目录的路径文本。"""
    ensure_repo_on_path()
    ensure_plugin_on_path(plugin_dir)
    relative = path.resolve().relative_to(plugin_dir.resolve())
    relative_text = relative.as_posix()
    digest = hashlib.sha256(relative_text.encode("utf-8")).hexdigest()[:10]
    module_name = f"loom_plugin_{plugin_dir.name}_{digest}_{path.stem}"
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise ScanError(f"无法加载插件模块: {relative_text}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except Exception as exc:  # noqa: BLE001 - 导入失败一律视为扫描失败
        raise ScanError(f"导入插件模块失败 {relative_text}: {exc}") from exc
    return module, relative_text


def _source_name(module: ModuleType, func: Any, key: str) -> str:
    """取函数在模块里的名字；循环批量生成的函数没有名字时退回节点键。"""
    for name, value in vars(module).items():
        if value is func and not name.startswith("_"):
            return name
    return key.rsplit(".", 1)[-1]


def collect_adapter_nodes(path: Path, plugin_dir: Path, adapter_type: str, sort_offset: int) -> list[Any]:
    """收集一个适配器模块里声明的事件或动作。"""
    before = loom_adapter.registry_size()
    module, relative_text = load_module(path, plugin_dir)
    specs = loom_adapter.registered_since(before)
    for index, spec in enumerate(specs):
        spec.connection_type = adapter_type
        spec.source_ref = f"{relative_text}#{_source_name(module, spec.func, spec.key)}"
        spec.sort = sort_offset + index
    return specs


def collect_workflow_nodes(path: Path, plugin_dir: Path, sort_offset: int) -> list[Any]:
    """收集一个节点模块里声明的工作流节点。"""
    before = loom_node.node_registry_size()
    module, relative_text = load_module(path, plugin_dir)
    specs = loom_node.nodes_registered_since(before)
    for index, spec in enumerate(specs):
        spec.source_ref = f"{relative_text}#{_source_name(module, spec.func, spec.key)}"
        spec.sort = sort_offset + index
    return specs


def collect_entities(path: Path, plugin_dir: Path) -> list[Any]:
    """收集一个实体模块里声明的实体类。"""
    before = loom_node.entity_registry_size()
    module, relative_text = load_module(path, plugin_dir)
    classes = loom_node.entities_registered_since(before)
    for cls in classes:
        setattr(cls, "__loom_entity_ref__", relative_text)
    return classes
