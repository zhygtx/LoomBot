"""工作流节点插件扫描：节点函数与实体类。"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from system.scanner.loader import collect_entities, collect_workflow_nodes, iter_python_files
from system.scanner.signature import (
    describe_entity,
    describe_parameters,
    describe_return,
    signature_hash,
)

NODES_DIR = "nodes"
ENTITIES_DIR = "entities"


def scan_entities(plugin_dir: Path) -> dict[str, dict[str, Any]]:
    """扫描实体目录，按类名建索引。"""
    entities: dict[str, dict[str, Any]] = {}
    for path in iter_python_files(plugin_dir / ENTITIES_DIR):
        for cls in collect_entities(path, plugin_dir):
            described = describe_entity(cls, cls.__name__)
            if described["name"] in entities:
                raise ValueError(f"重复声明的实体: {described['name']}")
            entities[described["name"]] = described
    return entities


def scan_workflow_nodes(
    plugin_dir: Path, entities: dict[str, dict[str, Any]]
) -> list[dict[str, Any]]:
    """扫描节点目录，产出节点目录条目。"""
    nodes: list[dict[str, Any]] = []
    for path in iter_python_files(plugin_dir / NODES_DIR):
        for spec in collect_workflow_nodes(path, plugin_dir, len(nodes)):
            parameters, var_positional, var_keyword = describe_parameters(spec.func)
            returned = describe_return(spec.func, entities)
            nodes.append(
                {
                    "nodeKey": spec.key,
                    "nodeType": "NODE",
                    "connectionType": None,
                    "name": spec.name,
                    "description": spec.description,
                    "category": spec.category,
                    "sourceRef": spec.source_ref,
                    "sort": spec.sort,
                    "signatureHash": signature_hash(
                        spec.key,
                        parameters,
                        var_positional,
                        var_keyword,
                        returned["returnType"],
                        returned["returnFields"],
                    ),
                    "parameters": parameters,
                    "varPositional": var_positional,
                    "varKeyword": var_keyword,
                    "returnType": returned["returnType"],
                    "returnFields": returned["returnFields"],
                }
            )
    return nodes
