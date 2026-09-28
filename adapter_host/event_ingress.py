from __future__ import annotations

import time
import uuid
from typing import Any

from adapter_host.redis_bus import RedisWorkflowBus


class EventIngress:
    """把插件事件补齐为平台信封后写入工作流事件流。"""

    def __init__(self, bus: RedisWorkflowBus) -> None:
        self.bus = bus

    async def publish(self, *, connection_id: int, revision: int, adapter_type: str, node_key: str,
                      payload: dict[str, Any], allowed_nodes: set[str]) -> dict[str, Any]:
        if node_key not in allowed_nodes:
            raise ValueError(f"插件未声明事件节点: {node_key}")
        if not isinstance(payload, dict):
            raise ValueError("事件负载必须是对象")
        envelope = {"eventId": str(uuid.uuid4()), "traceId": str(uuid.uuid4()), "connectionId": connection_id,
                    "connectionRevision": revision, "adapterType": adapter_type, "nodeKey": node_key,
                    "occurredAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "payload": payload}
        await self.bus.publish_event(envelope)
        return envelope
