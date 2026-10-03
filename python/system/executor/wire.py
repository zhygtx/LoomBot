"""执行器 ↔ 节点宿主进程之间的值编解码。

节点参数经 `convert()` 之后可能是 `bytes` / `Path` / `UUID` / `Decimal` / `datetime`
这些 JSON 装不下的类型，节点返回值还可能是数据类。直接 `json.dumps` 会把它们压成字符串，
插件拿到的就不是原本的类型了。这里给这些值加一层标记，过一遍 JSON 还能还原成同一种值。

认不出来的对象按数据类展开，再不行退回字符串——和日志编码的兜底策略保持一致，
不静默丢数据。
"""

from __future__ import annotations

import base64
import dataclasses
import json
from datetime import date, datetime, time
from decimal import Decimal
from pathlib import Path
from typing import Any
from uuid import UUID

TAG = "__loombot__"


def dumps(value: Any) -> str:
    """编码成一行 JSON 文本（不含换行）。"""
    return json.dumps(value, ensure_ascii=False, default=_encode)


def loads(text: str) -> Any:
    """把上面编码出来的文本还原成带类型的值。"""
    return json.loads(text, object_hook=_decode)


def _encode(value: Any) -> Any:
    """`json.dumps` 的 default 钩子：只处理 JSON 原生装不下的类型。"""
    if isinstance(value, (bytes, bytearray, memoryview)):
        return {TAG: "bytes", "value": base64.b64encode(bytes(value)).decode("ascii")}
    # datetime 是 date 的子类，必须先判。
    if isinstance(value, datetime):
        return {TAG: "datetime", "value": value.isoformat()}
    if isinstance(value, date):
        return {TAG: "date", "value": value.isoformat()}
    if isinstance(value, time):
        return {TAG: "time", "value": value.isoformat()}
    if isinstance(value, Decimal):
        return {TAG: "decimal", "value": str(value)}
    if isinstance(value, UUID):
        return {TAG: "uuid", "value": str(value)}
    if isinstance(value, Path):
        return {TAG: "path", "value": str(value)}
    if isinstance(value, (set, frozenset)):
        # 元素本身由外层 json 继续递归编码，这里不用自己动手。
        return {TAG: "set" if isinstance(value, set) else "frozenset", "value": list(value)}
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return dataclasses.asdict(value)
    return {TAG: "str", "value": str(value)}


def _decode(value: Any) -> Any:
    """`json.loads` 的 object_hook：只认带标记的字典，其它原样返回。"""
    if not isinstance(value, dict):
        return value
    tag = value.get(TAG)
    if not isinstance(tag, str):
        return value
    raw = value.get("value")
    try:
        if tag == "bytes":
            return base64.b64decode(raw)
        if tag == "datetime":
            return datetime.fromisoformat(raw)
        if tag == "date":
            return date.fromisoformat(raw)
        if tag == "time":
            return time.fromisoformat(raw)
        if tag == "decimal":
            return Decimal(str(raw))
        if tag == "uuid":
            return UUID(str(raw))
        if tag == "path":
            return Path(str(raw))
        if tag == "set":
            return set(raw or [])
        if tag == "frozenset":
            return frozenset(raw or [])
        if tag == "str":
            return str(raw)
    except (TypeError, ValueError):
        # 标记坏了就按原样交出去，让上层看到真实内容而不是抛一个解码异常。
        return raw
    return raw
