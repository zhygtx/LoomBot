"""执行上下文与适配器动作客户端。"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

import httpx

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

    def __init__(self, base_url: str, token: str, timeout: float = 30.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    async def call(self, connection_id: int, node_key: str, params: dict[str, Any]) -> Any:
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
            raise ActionError(f"调用适配器动作失败: {exc}") from exc
        status = str(payload.get("status") or "")
        if status != "SUCCEEDED":
            raise ActionError(
                str(payload.get("errorMessage") or f"动作返回 {status}"),
                code=str(payload.get("errorCode") or "ACTION_FAILED"),
            )
        return payload.get("result")
