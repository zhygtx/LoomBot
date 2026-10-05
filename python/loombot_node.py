"""工作流节点插件 SDK：节点、实体与参数说明的装饰器。

插件只从本模块导入 `node`、`entity`、`Param` 和 `Attribute`。
"""

from __future__ import annotations

import inspect
import sys
from dataclasses import dataclass, field
from typing import Any, Callable

_NODES: list["NodeSpec"] = []
_ENTITIES: list[Any] = []
_SHUTDOWN_HOOKS: list[Callable[[], Any]] = []


def on_shutdown(func: Callable[[], Any]) -> Callable[[], Any]:
    """注册节点进程退出时的清理回调，同步/异步函数都支持。

    插件常驻资源（浏览器、线程池、临时目录）在这里释放；节点宿主进程收到
    shutdown 或 stdin 关闭时按注册顺序倒序执行。
    """
    _SHUTDOWN_HOOKS.append(func)
    return func


async def run_shutdown_hooks() -> None:
    """执行并清空已注册的退出回调；清理失败只记日志，不影响进程退出。"""
    while _SHUTDOWN_HOOKS:
        hook = _SHUTDOWN_HOOKS.pop()
        try:
            result = hook()
            if inspect.isawaitable(result):
                await result
        except Exception as exc:  # noqa: BLE001 - 退出阶段的异常不能拦住进程
            print(
                f"[loombot_node] shutdown hook {getattr(hook, '__name__', hook)} failed: {exc}",
                file=sys.stderr,
            )


def node_registry_size() -> int:
    """当前已注册的节点数量，供扫描器取导入期间的增量。"""
    return len(_NODES)


def nodes_registered_since(index: int) -> list["NodeSpec"]:
    return list(_NODES[index:])


def entity_registry_size() -> int:
    """当前已注册的实体数量。"""
    return len(_ENTITIES)


def entities_registered_since(index: int) -> list[Any]:
    return list(_ENTITIES[index:])


@dataclass(frozen=True)
class Param:
    """参数说明，配合 `Annotated[T, Param(...)]` 使用。

    默认所有参数都必须在画布上显式配置（填默认值或引用前置节点），运行时不会拿函数签名里的
    Python 默认值兜底。确实允许留空的参数要显式声明 `nullable=True`，留空时运行时传 `None`。
    """

    description: str = ""
    name: str | None = None
    nullable: bool = False


@dataclass(frozen=True)
class Attribute:
    """实体字段说明，配合 `Annotated[T, Attribute(...)]` 使用。"""

    description: str = ""
    name: str | None = None


@dataclass
class NodeSpec:
    """一个工作流节点的声明。"""

    key: str
    name: str
    category: str = ""
    description: str = ""
    source_ref: str = ""
    sort: int = 0
    func: Callable[..., Any] | None = field(default=None, repr=False)


def _require_key(key: str) -> str:
    value = str(key or "").strip()
    if not value:
        raise ValueError("节点键不能为空")
    return value


def node(
    key: str,
    *,
    name: str,
    category: str = "",
    description: str = "",
) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """声明一个工作流节点。函数第一个位置参数必须是 `ctx`。"""
    node_key = _require_key(key)
    node_name = str(name or "").strip()
    if not node_name:
        raise ValueError(f"节点 {node_key} 缺少展示名 name")

    def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
        if not callable(func):
            raise TypeError(f"节点 {node_key} 必须装饰函数")
        signature = inspect.signature(func)
        parameters = list(signature.parameters.values())
        for parameter in parameters:
            if parameter.kind in (inspect.Parameter.VAR_POSITIONAL, inspect.Parameter.VAR_KEYWORD):
                # *args / **kwargs 对应画布的额外输入，允许出现在 ctx 之后。
                continue
        if not parameters or parameters[0].name != "ctx":
            raise TypeError(
                f"节点 {node_key} 的第一个位置参数必须是 ctx：{getattr(func, '__qualname__', func)}"
            )
        if parameters[0].kind not in (
            inspect.Parameter.POSITIONAL_ONLY,
            inspect.Parameter.POSITIONAL_OR_KEYWORD,
        ):
            raise TypeError(f"节点 {node_key} 的 ctx 必须是普通位置参数")
        setattr(
            func,
            "__loombot_node__",
            NodeSpec(
                key=node_key,
                name=node_name,
                category=str(category or "").strip(),
                description=description,
                func=func,
            ),
        )
        _NODES.append(getattr(func, "__loombot_node__"))
        return func

    return decorator


def entity(cls: Any = None, *, description: str = "") -> Any:
    """声明一个实体类，字段用 `Annotated[T, Attribute(...)]` 补充说明。"""

    def wrap(target: Any) -> Any:
        setattr(target, "__loombot_entity__", {"description": description})
        _ENTITIES.append(target)
        return target

    return wrap(cls) if cls is not None else wrap
