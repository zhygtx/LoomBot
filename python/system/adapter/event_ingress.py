"""事件入口：补全事件信封，查触发索引并投递工作流任务。"""

from __future__ import annotations

import logging
import time
import uuid
from typing import Any

from system.adapter.redis_bus import RedisWorkflowBus

log = logging.getLogger("adapter-ingress")


class EventIngress:
    """把插件事件补齐为平台信封后交给工作流。

    触发决策只看 Redis 触发索引：没有候选事件直接丢弃（心跳走的就是这条路），
    有候选才为每个候选定义版本写一条任务。事件审计流默认关闭，只在排查时开启。
    """

    def __init__(self, bus: RedisWorkflowBus, audit_enabled: bool = False) -> None:
        self.bus = bus
        self.audit_enabled = audit_enabled

    async def publish(
        self,
        *,
        connection_id: int,
        revision: int,
        connection_type: str,
        plugin_version_id: int,
        node_key: str,
        payload: dict[str, Any],
        allowed_nodes: set[str],
    ) -> dict[str, Any]:
        if node_key not in allowed_nodes:
            raise ValueError(f"插件未声明事件节点: {node_key}")
        if not isinstance(payload, dict):
            raise ValueError("事件负载必须是对象")
        envelope = {
            "eventId": str(uuid.uuid4()),
            "traceId": str(uuid.uuid4()),
            "connectionId": connection_id,
            "connectionRevision": revision,
            "adapterType": connection_type,
            "pluginVersionId": plugin_version_id,
            "nodeKey": node_key,
            "occurredAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "payload": payload,
        }
        if self.audit_enabled:
            await self.bus.publish_event(envelope)

        workflow_version_ids = await self.bus.workflows_for(
            connection_id, connection_type, node_key
        )
        for workflow_version_id in workflow_version_ids:
            await self.bus.publish_task(
                connection_id=connection_id,
                connection_type=connection_type,
                plugin_version_id=plugin_version_id,
                node_key=node_key,
                workflow_version_id=workflow_version_id,
                event=payload,
                trace_id=envelope["traceId"],
                event_id=envelope["eventId"],
            )
        if not workflow_version_ids:
            log.debug(
                "事件没有候选工作流: connection=%s type=%s node=%s",
                connection_id,
                connection_type,
                node_key,
            )
        envelope["workflowVersionIds"] = workflow_version_ids
        return envelope
