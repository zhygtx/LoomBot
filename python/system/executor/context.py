"""执行上下文与适配器动作客户端。"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from system.executor.errors import ActionError


@dataclass
class ExecutionContext:
    """节点函数看到的执行上下文。"""

    execution_id: str
    trace_id: str
    workflow_id: int
    workflow_version_id: int
    node_id: str
    node_key: str
    deadline_ms: int
    trigger: dict[str, Any]
    action_caller: Callable[[int, str, dict[str, Any]], Awaitable[Any]]
    logger: logging.Logger = field(
        default_factory=lambda: logging.getLogger("workflow-node")
    )

    async def call_action(
        self, connection_id: int, node_key: str, params: dict[str, Any]
    ) -> Any:
        """调用适配器动作；插件不直接持有平台连接。"""
        return await self.action_caller(int(connection_id), str(node_key), dict(params or {}))


class ActionClient:
    """通过适配器控制面调用平台动作。"""

    # 必须比适配器侧的整条链路更长（worker 35s + IPC 45s），否则又是我们先把连接掐掉，
    # 拿到的是 httpx 的空超时错误而不是适配器给出的"平台未返回响应"。
    # 超时阶梯：插件 echo 30s < worker 35s < supervisor 45s < 本值 55s < 前端 90s。
    def __init__(self, base_url: str, token: str, timeout: float = 55.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    async def call(self, connection_id: int, node_key: str, params: dict[str, Any]) -> Any:
        # httpx 只在宿主进程用得到：节点宿主进程要复用 ExecutionContext，不能被这个依赖拖住，
        # 所以 import 放在方法里。
        import httpx

        url = f"{self.base_url}/internal/actions/{int(connection_id)}"
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.post(
                    url,
                    headers={"X-Adapter-Token": self.token},
                    json={"action": node_key, "params": params},
                )
                response.raise_for_status()
                payload = response.json()
        except Exception as exc:  # noqa: BLE001 - 网络与控制面错误统一分类
            # httpx 的超时异常 str() 是空的，只写 {exc} 会得到一句"调用适配器动作失败:"，
            # 排查时完全看不出发生了什么，所以补上异常类型。
            detail = str(exc).strip() or type(exc).__name__
            raise ActionError(f"调用适配器动作失败（{type(exc).__name__}）: {detail}") from exc
        status = str(payload.get("status") or "")
        if status != "SUCCEEDED":
            raise ActionError(
                str(payload.get("errorMessage") or f"动作返回 {status}"),
                code=str(payload.get("errorCode") or "ACTION_FAILED"),
            )
        return payload.get("result")
