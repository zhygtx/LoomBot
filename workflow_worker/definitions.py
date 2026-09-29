"""从 Java 控制面按版本拉取工作流定义。"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from workflow_worker.errors import DefinitionError

log = logging.getLogger("workflow-definitions")


class DefinitionClient:
    """定义版本不可变，因此可以永久缓存，只在进程重启或版本删除时失效。"""

    def __init__(self, base_url: str, token: str, timeout: float = 10.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout
        self._cache: dict[int, dict[str, Any]] = {}

    async def fetch(self, version_id: int) -> dict[str, Any]:
        cached = self._cache.get(version_id)
        if cached is not None:
            return cached
        url = f"{self.base_url}/internal/workflow/versions/{int(version_id)}"
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.get(url, headers={"X-Workflow-Token": self.token})
                if response.status_code == 404:
                    raise DefinitionError(f"定义版本不存在: {version_id}")
                response.raise_for_status()
                payload = response.json()
        except DefinitionError:
            raise
        except Exception as exc:  # noqa: BLE001 - 控制面不可达统一分类
            raise DefinitionError(f"拉取定义失败: {exc}") from exc
        definition = payload.get("definition")
        if not isinstance(definition, dict):
            raise DefinitionError(f"定义版本 {version_id} 返回内容不合法")
        plugins: dict[int, dict[str, Any]] = {}
        for item in payload.get("plugins") or []:
            try:
                plugins[int(item["pluginVersionId"])] = item
            except (KeyError, TypeError, ValueError):
                continue
        result = {
            "workflowId": payload.get("workflowId"),
            "versionId": payload.get("versionId"),
            "versionNo": payload.get("versionNo"),
            "definition": definition,
            "plugins": plugins,
        }
        self._cache[version_id] = result
        return result
