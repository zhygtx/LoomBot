"""按声明类型做确定性转换；失败时给出可读错误，不静默降级。"""

from __future__ import annotations

import json
from datetime import date, datetime, time
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any
from uuid import UUID

from workflow_worker.errors import ParamError

_SCALARS = {"str", "int", "float", "bool", "bytes", "Decimal", "UUID", "Path"}
_CONTAINERS = {"list", "dict", "set", "tuple", "frozenset"}
_TEMPORALS = {"datetime", "date", "time"}


def convert(value: Any, target_type: str, multi_type: bool = False) -> Any:
    """把画布上的值转成声明类型。"""
    if value is None:
        return None
    key = str(target_type or "object").strip()
    if multi_type or "|" in key:
        for candidate in key.split("|"):
            try:
                return convert(value, candidate.strip(), False)
            except ParamError:
                continue
        # 联合类型全部失败时原样传递，由插件自己处理
        return value
    if key in {"object", "any", "Literal", ""}:
        return value
    if key == "str":
        if isinstance(value, bool):
            return "true" if value else "false"
        return value if isinstance(value, str) else str(value)
    if key == "int":
        try:
            return int(value)
        except (TypeError, ValueError) as exc:
            raise ParamError(f"期望 int，实际收到 {value!r}") from exc
    if key == "float":
        try:
            return float(value)
        except (TypeError, ValueError) as exc:
            raise ParamError(f"期望 float，实际收到 {value!r}") from exc
    if key == "bool":
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)) and value in (0, 1):
            return bool(value)
        if isinstance(value, str) and value.strip().lower() in {"true", "false"}:
            return value.strip().lower() == "true"
        raise ParamError(f"期望 bool，实际收到 {value!r}")
    if key == "bytes":
        if isinstance(value, bytes):
            return value
        if isinstance(value, str):
            return value.encode("utf-8")
        raise ParamError(f"期望 bytes，实际收到 {type(value).__name__}")
    if key in _CONTAINERS:
        parsed = value
        if isinstance(parsed, str):
            try:
                parsed = json.loads(parsed)
            except (TypeError, ValueError) as exc:
                raise ParamError(f"期望 {key}，字符串不是合法 JSON：{value!r}") from exc
        if key == "list" and isinstance(parsed, (list, tuple, set, frozenset)):
            return list(parsed)
        if key == "dict" and isinstance(parsed, dict):
            return parsed
        if key == "set" and isinstance(parsed, (list, tuple, set, frozenset)):
            return set(parsed)
        if key == "tuple" and isinstance(parsed, (list, tuple, set, frozenset)):
            return tuple(parsed)
        if key == "frozenset" and isinstance(parsed, (list, tuple, set, frozenset)):
            return frozenset(parsed)
        raise ParamError(f"期望 {key}，实际收到 {type(parsed).__name__}")
    if key == "Decimal":
        if isinstance(value, Decimal):
            return value
        try:
            return Decimal(str(value))
        except (InvalidOperation, ValueError) as exc:
            raise ParamError(f"期望 Decimal，实际收到 {value!r}") from exc
    if key == "UUID":
        if isinstance(value, UUID):
            return value
        try:
            return UUID(str(value))
        except (TypeError, ValueError) as exc:
            raise ParamError(f"期望 UUID，实际收到 {value!r}") from exc
    if key == "Path":
        return value if isinstance(value, Path) else Path(str(value))
    if key in _TEMPORALS:
        parsed = _to_datetime(value, key)
        if key == "date" and isinstance(parsed, datetime):
            return parsed.date()
        if key == "time" and isinstance(parsed, datetime):
            return parsed.time()
        return parsed
    # 枚举与实体：交给插件自己按值构造
    return value


def _to_datetime(value: Any, key: str) -> Any:
    if isinstance(value, datetime):
        return value
    if isinstance(value, date) and not isinstance(value, datetime):
        return value
    if isinstance(value, time):
        return value
    if isinstance(value, str):
        try:
            return datetime.fromisoformat(value)
        except ValueError as exc:
            raise ParamError(f"期望 {key}，无法解析时间：{value!r}") from exc
    if isinstance(value, (int, float)):
        try:
            return datetime.fromtimestamp(value)
        except (ValueError, OSError, OverflowError) as exc:
            raise ParamError(f"期望 {key}，时间戳非法：{value!r}") from exc
    raise ParamError(f"期望 {key}，实际收到 {type(value).__name__}")
