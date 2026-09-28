from __future__ import annotations

import time
import uuid
from typing import Any

PROTOCOL_VERSION = 1
MAX_MESSAGE_BYTES = 2 * 1024 * 1024


def now_ms() -> int:
    return int(time.time() * 1000)


def message(kind: str, *, request_id: str | None = None, connection_id: int | None = None, **body: Any) -> dict[str, Any]:
    value: dict[str, Any] = {"protocolVersion": PROTOCOL_VERSION, "kind": kind, "requestId": request_id or uuid.uuid4().hex}
    if connection_id is not None:
        value["connectionId"] = connection_id
    value.update(body)
    return value


def require_message(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or value.get("protocolVersion") != PROTOCOL_VERSION:
        raise ValueError("不支持的进程间协议版本")
    if not isinstance(value.get("kind"), str):
        raise ValueError("进程间消息缺少 kind")
    return value
