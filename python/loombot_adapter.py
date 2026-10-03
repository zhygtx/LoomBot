"""适配器插件 SDK：事件与动作的装饰器。

插件只从本模块导入 `event` 和 `action`，不接触宿主内部对象。
"""

from __future__ import annotations

import inspect
from dataclasses import dataclass, field
from typing import Any, Callable

EVENT = "EVENT"
ACTION = "ACTION"

_REGISTERED: list["NodeSpec"] = []


def registry_size() -> int:
    """当前已注册的节点数量，供扫描器取导入期间的增量。"""
    return len(_REGISTERED)


def registered_since(index: int) -> list["NodeSpec"]:
    """返回从 index 之后新注册的节点声明。"""
    return list(_REGISTERED[index:])


@dataclass
class NodeSpec:
    """一个事件节点或动作节点的声明。"""

    key: str
    kind: str
    name: str
    description: str = ""
    payload_schema: dict[str, Any] | None = None
    params_schema: dict[str, Any] | None = None
    result_schema: dict[str, Any] | None = None
    connection_type: str | None = None
    source_ref: str = ""
    sort: int = 0
    func: Callable[..., Any] | None = field(default=None, repr=False)

    def as_dict(self) -> dict[str, Any]:
        return {
            "nodeKey": self.key,
            "nodeType": self.kind,
            "connectionType": self.connection_type,
            "name": self.name,
            "description": self.description,
            "sourceRef": self.source_ref,
            "sort": self.sort,
            "payloadSchema": self.payload_schema,
            "paramsSchema": self.params_schema,
            "resultSchema": self.result_schema,
        }


def _require_key(key: str, what: str) -> str:
    value = str(key or "").strip()
    if not value:
        raise ValueError(f"{what}的节点键不能为空")
    return value


def _require_name(name: str, key: str) -> str:
    value = str(name or "").strip()
    if not value:
        raise ValueError(f"节点 {key} 缺少展示名 name")
    return value


def _attach(func: Callable[..., Any], spec: NodeSpec) -> Callable[..., Any]:
    """校验签名并挂上声明。签名不合法直接失败，不静默跳过。"""
    if not callable(func):
        raise TypeError(f"节点 {spec.key} 必须装饰函数")
    if not inspect.iscoroutinefunction(func):
        raise TypeError(f"节点 {spec.key} 必须是 async def：{getattr(func, '__qualname__', func)}")
    signature = inspect.signature(func)
    parameters = list(signature.parameters.values())
    for parameter in parameters:
        if parameter.kind in (inspect.Parameter.VAR_POSITIONAL, inspect.Parameter.VAR_KEYWORD):
            raise TypeError(f"节点 {spec.key} 不接受可变参数：{parameter.name}")
    positional = [
        parameter
        for parameter in parameters
        if parameter.kind in (inspect.Parameter.POSITIONAL_ONLY, inspect.Parameter.POSITIONAL_OR_KEYWORD)
    ]
    if len(positional) != 2 or len(positional) != len(parameters):
        raise TypeError(
            f"节点 {spec.key} 必须正好接受两个位置参数 "
            f"（事件为 (conn, frame)，动作为 (conn, params)）：{getattr(func, '__qualname__', func)}"
        )
    spec.func = func
    setattr(func, "__loombot_node__", spec)
    _REGISTERED.append(spec)
    return func


def event(
    key: str,
    *,
    name: str,
    description: str = "",
    payload_schema: dict[str, Any] | None = None,
) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """声明一个事件节点。函数签名必须是 `async def handler(conn, frame)`。"""
    node_key = _require_key(key, "事件")
    node_name = _require_name(name, node_key)

    def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
        return _attach(
            func,
            NodeSpec(
                key=node_key,
                kind=EVENT,
                name=node_name,
                description=description,
                payload_schema=payload_schema,
            ),
        )

    return decorator


def action(
    key: str,
    *,
    name: str,
    description: str = "",
    params_schema: dict[str, Any] | None = None,
    result_schema: dict[str, Any] | None = None,
) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """声明一个动作节点。函数签名必须是 `async def handler(conn, params)`。"""
    node_key = _require_key(key, "动作")
    node_name = _require_name(name, node_key)

    def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
        return _attach(
            func,
            NodeSpec(
                key=node_key,
                kind=ACTION,
                name=node_name,
                description=description,
                params_schema=params_schema,
                result_schema=result_schema,
            ),
        )

    return decorator
