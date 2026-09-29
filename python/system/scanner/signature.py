"""节点函数的签名内省：参数、返回值字段与签名哈希。"""

from __future__ import annotations

import base64
import dataclasses
import enum
import hashlib
import inspect
import json
import types
import typing
from datetime import date, datetime, time
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable
from uuid import UUID

from loom_node import Attribute, Param

_PRIMITIVES: dict[Any, str] = {
    str: "str",
    int: "int",
    float: "float",
    bool: "bool",
    bytes: "bytes",
    list: "list",
    dict: "dict",
    set: "set",
    tuple: "tuple",
    frozenset: "frozenset",
    Decimal: "Decimal",
    UUID: "UUID",
    Path: "Path",
    datetime: "datetime",
    date: "date",
    time: "time",
}

_CONTAINERS = {list: "list", dict: "dict", set: "set", tuple: "tuple", frozenset: "frozenset"}


def type_hints(target: Any) -> dict[str, Any]:
    """取类型注解；注解求值失败时退回原始注解，不让扫描直接失败。"""
    try:
        return typing.get_type_hints(target, include_extras=True)
    except Exception:  # noqa: BLE001 - 注解不可求值时按原始注解处理
        return dict(getattr(target, "__annotations__", {}) or {})


def json_safe(value: Any) -> Any:
    """把默认值转成可 JSON 序列化的形式。"""
    if isinstance(value, bytes):
        return {"__bytes__": base64.b64encode(value).decode("ascii")}
    if isinstance(value, complex):
        return str(value)
    if isinstance(value, enum.Enum):
        return value.value
    if isinstance(value, (set, frozenset, tuple, list)):
        return [json_safe(item) for item in value]
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (datetime, date, time)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return json_safe(dataclasses.asdict(value))
    return str(value)


def describe_annotation(annotation: Any) -> dict[str, Any]:
    """把一个类型注解解析成节点目录里的类型描述。"""
    result: dict[str, Any] = {
        "type": "object",
        "nullable": False,
        "multiType": False,
        "literals": [],
        "enumValues": [],
        "meta": None,
    }
    if annotation is None or annotation is inspect.Signature.empty:
        return result

    if hasattr(annotation, "__metadata__"):
        metadata = tuple(getattr(annotation, "__metadata__", ()) or ())
        for item in metadata:
            if isinstance(item, (Param, Attribute)):
                result["meta"] = item
                break
        inner = describe_annotation(getattr(annotation, "__origin__", annotation))
        if result["meta"] is not None:
            inner["meta"] = result["meta"]
            if bool(getattr(result["meta"], "nullable", False)):
                inner["nullable"] = True
        return inner

    origin = typing.get_origin(annotation)
    if origin in (typing.Union, types.UnionType):
        members = [item for item in typing.get_args(annotation) if item is not type(None)]
        nullable = len(members) != len(typing.get_args(annotation))
        if len(members) == 1:
            inner = describe_annotation(members[0])
            inner["nullable"] = inner["nullable"] or nullable
            return inner
        names = [describe_annotation(item)["type"] for item in members]
        result["type"] = "|".join(names)
        result["multiType"] = True
        result["nullable"] = nullable
        return result

    if origin is typing.Literal:
        literals = [item for item in typing.get_args(annotation)]
        result["type"] = "Literal"
        result["literals"] = [json_safe(item) for item in literals]
        return result

    if origin in _CONTAINERS:
        result["type"] = _CONTAINERS[origin]
        return result

    if isinstance(annotation, type):
        if annotation in _PRIMITIVES:
            result["type"] = _PRIMITIVES[annotation]
            return result
        if issubclass(annotation, enum.Enum):
            result["type"] = annotation.__name__
            result["enumValues"] = [json_safe(member.value) for member in annotation]
            return result
        result["type"] = annotation.__name__
        return result

    text = getattr(annotation, "__name__", None) or str(annotation)
    result["type"] = text.replace("typing.", "")
    return result


def describe_parameters(func: Callable[..., Any]) -> tuple[list[dict[str, Any]], bool, bool]:
    """解析节点参数；`ctx` 不进入参数列表。"""
    signature = inspect.signature(func)
    hints = type_hints(func)
    parameters: list[dict[str, Any]] = []
    var_positional = False
    var_keyword = False
    first = True
    for name, parameter in signature.parameters.items():
        if first:
            first = False
            if name == "ctx":
                continue
        if parameter.kind is inspect.Parameter.VAR_POSITIONAL:
            var_positional = True
            continue
        if parameter.kind is inspect.Parameter.VAR_KEYWORD:
            var_keyword = True
            continue
        described = describe_annotation(hints.get(name, parameter.annotation))
        meta = described["meta"]
        has_default = parameter.default is not inspect.Parameter.empty
        default = parameter.default if has_default else None
        if not has_default and described["literals"]:
            default = described["literals"][0]
            has_default = True
        parameters.append(
            {
                "name": name,
                "displayName": (getattr(meta, "name", None) or name) if meta else name,
                "type": described["type"],
                # 只有显式声明 nullable 的参数才允许留空；函数签名里的 Python 默认值只是
                # 提示，运行时不会自动兜底，所以它不再让参数变成可选。
                "required": not described["nullable"],
                "nullable": described["nullable"],
                "multiType": described["multiType"],
                "literals": described["literals"],
                "enumValues": described["enumValues"],
                "kind": "KEYWORD_ONLY"
                if parameter.kind is inspect.Parameter.KEYWORD_ONLY
                else "POSITIONAL",
                "hasDefault": has_default,
                "defaultValue": json_safe(default) if has_default else None,
                "description": getattr(meta, "description", "") if meta else "",
                "order": len(parameters),
            }
        )
    return parameters, var_positional, var_keyword


def describe_return(func: Callable[..., Any], entities: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """解析返回值类型与可映射字段。"""
    annotation = type_hints(func).get("return", inspect.Signature.empty)
    whole = {"name": "整个返回值", "type": "void", "path": "", "description": ""}
    if annotation is inspect.Signature.empty or annotation is None or annotation is type(None):
        return {"returnType": "void", "returnFields": [whole]}
    described = describe_annotation(annotation)
    fields = [
        {
            "name": "整个返回值",
            "type": described["type"],
            "path": "",
            "description": "",
            "depth": 0,
        }
    ]
    entity = entities.get(described["type"])
    if entity:
        _append_entity_fields(described["type"], "", fields, entities, {described["type"]})
    return {"returnType": described["type"], "returnFields": fields}


def _append_entity_fields(
    entity_name: str,
    prefix: str,
    fields: list[dict[str, Any]],
    entities: dict[str, dict[str, Any]],
    seen: set[str],
) -> None:
    entity = entities.get(entity_name)
    if not entity:
        return
    for field in entity["fields"]:
        key = str(field["key"])
        path = f"{prefix}.{key}" if prefix else key
        fields.append(
            {
                "name": field["name"],
                "key": key,
                "type": field["type"],
                "path": path,
                "description": field.get("description", ""),
                "depth": path.count("."),
            }
        )
        nested_type = str(field["type"])
        if nested_type in entities and nested_type not in seen:
            _append_entity_fields(
                nested_type,
                path,
                fields,
                entities,
                {*seen, nested_type},
            )


def describe_entity(cls: type, name: str) -> dict[str, Any]:
    """解析实体类的字段。"""
    meta = getattr(cls, "__loom_entity__", {}) or {}
    hints = type_hints(cls)
    field_names: list[str] = []
    if dataclasses.is_dataclass(cls):
        field_names = [field.name for field in dataclasses.fields(cls)]
    else:
        field_names = [key for key in hints if key != "return"]
    fields: list[dict[str, Any]] = []
    for field_name in field_names:
        described = describe_annotation(hints.get(field_name, inspect.Signature.empty))
        attribute = described["meta"]
        fields.append(
            {
                "key": field_name,
                "name": (getattr(attribute, "name", None) or field_name) if attribute else field_name,
                "type": described["type"],
                "description": getattr(attribute, "description", "") if attribute else "",
            }
        )
    return {
        "name": name,
        "sourceRef": f"{getattr(cls, '__loom_entity_ref__', '')}#{name}",
        "description": str(meta.get("description") or ""),
        "fields": fields,
    }


def signature_hash(
    key: str,
    parameters: list[dict[str, Any]],
    var_positional: bool,
    var_keyword: bool,
    return_type: str,
    return_fields: list[dict[str, Any]],
) -> str:
    """计算影响工作流兼容性的签名哈希。"""
    payload = {
        "key": key,
        "parameters": [
            {
                "name": item["name"],
                "type": item["type"],
                "nullable": item["nullable"],
                "kind": item["kind"],
                "default": item["defaultValue"] if item["hasDefault"] else "__no_default__",
            }
            for item in sorted(parameters, key=lambda value: value["order"])
        ],
        "varPositional": var_positional,
        "varKeyword": var_keyword,
        "returnType": return_type,
        "returnFields": sorted(
            [{"path": item["path"], "type": item["type"]} for item in return_fields],
            key=lambda value: value["path"],
        ),
    }
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]
