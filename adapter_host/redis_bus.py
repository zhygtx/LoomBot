from __future__ import annotations

import json
import time
import uuid
from typing import Any

import redis.asyncio as redis


class RedisWorkflowBus:
    def __init__(self, redis_url: str, task_stream: str, index_prefix: str, ttl_seconds: int):
        self._redis = redis.from_url(redis_url, decode_responses=True)
        self._task_stream = task_stream
        self._index_prefix = index_prefix
        self._ttl_seconds = ttl_seconds
        self._deadline_key = f"{task_stream}:deadlines"
        self._event_stream = f"{task_stream}:events"

    async def close(self) -> None:
        await self._redis.aclose()

    def _index_key(self, connection_id: int, connection_type: str, node_key: str) -> str:
        return f"{self._index_prefix}:{connection_id}:{connection_type}:{node_key}"

    async def workflows_for(
        self, connection_id: int, connection_type: str, node_key: str
    ) -> list[int]:
        raw = await self._redis.smembers(
            self._index_key(connection_id, connection_type, node_key)
        )
        values: list[int] = []
        for value in raw:
            try:
                values.append(int(value))
            except (TypeError, ValueError):
                continue
        return sorted(set(values))

    async def publish_task(
        self,
        *,
        connection_id: int,
        connection_type: str,
        plugin_version_id: int,
        node_key: str,
        workflow_version_id: int,
        event: dict[str, Any],
        trace_id: str | None = None,
        event_id: str | None = None,
    ) -> str:
        now = int(time.time() * 1000)
        deadline = now + self._ttl_seconds * 1000
        execution_id = str(uuid.uuid4())
        message_id = str(uuid.uuid4())
        fields = {
            "messageId": message_id,
            "executionId": execution_id,
            "eventId": event_id or "",
            "traceId": trace_id or uuid.uuid4().hex,
            "connectionId": str(connection_id),
            "connectionType": connection_type,
            "pluginVersionId": str(plugin_version_id),
            "nodeKey": node_key,
            "workflowVersionId": str(workflow_version_id),
            "event": json.dumps(event, ensure_ascii=False, separators=(",", ":")),
            "createdAt": str(now),
            "deadline": str(deadline),
        }
        record_id = await self._redis.xadd(self._task_stream, fields)
        await self._redis.zadd(self._deadline_key, {record_id: deadline})
        return record_id

    async def publish_event(self, envelope: dict[str, Any]) -> str:
        """写入统一事件流；工作流消费者按 eventId 做幂等。"""
        fields = {
            "eventId": str(envelope["eventId"]),
            "traceId": str(envelope["traceId"]),
            "connectionId": str(envelope["connectionId"]),
            "connectionRevision": str(envelope["connectionRevision"]),
            "adapterType": str(envelope["adapterType"]),
            "nodeKey": str(envelope["nodeKey"]),
            "occurredAt": str(envelope["occurredAt"]),
            "payload": json.dumps(envelope["payload"], ensure_ascii=False, separators=(",", ":")),
        }
        return await self._redis.xadd(self._event_stream, fields, maxlen=100000, approximate=True)

    async def publish_scheduled_task(
        self,
        *,
        workflow_version_id: int,
        node_key: str,
        event: dict[str, Any] | None = None,
    ) -> str:
        """定时触发投递的任务：没有连接来源，字段留空。"""
        now = int(time.time() * 1000)
        deadline = now + self._ttl_seconds * 1000
        fields = {
            "messageId": str(uuid.uuid4()),
            "executionId": str(uuid.uuid4()),
            "eventId": "",
            "traceId": uuid.uuid4().hex,
            "connectionId": "",
            "connectionType": "",
            "pluginVersionId": "",
            "nodeKey": node_key,
            "workflowVersionId": str(workflow_version_id),
            "event": json.dumps(event or {"triggerTime": now}, ensure_ascii=False, separators=(",", ":")),
            "createdAt": str(now),
            "deadline": str(deadline),
        }
        record_id = await self._redis.xadd(self._task_stream, fields)
        await self._redis.zadd(self._deadline_key, {record_id: deadline})
        return record_id

    async def cleanup_expired(self) -> int:
        now = int(time.time() * 1000)
        ids = await self._redis.zrangebyscore(self._deadline_key, 0, now)
        if not ids:
            return 0
        await self._redis.xdel(self._task_stream, *ids)
        await self._redis.zrem(self._deadline_key, *ids)
        return len(ids)
