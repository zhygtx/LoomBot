"""定时触发调度器：Cron 表达式在适配器层求值，到点投递工作流任务。

支持 5 或 6 字段（秒 分 时 日 月 周），`?` 等价 `*`，周字段 1-7 且 1 表示周日（与 Quartz 一致）。
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from adapter_host.redis_bus import RedisWorkflowBus

log = logging.getLogger("adapter-scheduler")


class CronError(ValueError):
    """Cron 表达式非法。"""


def _parse_field(text: str, minimum: int, maximum: int) -> set[int]:
    values: set[int] = set()
    for raw in str(text or "").split(","):
        part = raw.strip()
        if not part:
            raise CronError("Cron 字段为空")
        step = 1
        if "/" in part:
            part, _, step_text = part.partition("/")
            try:
                step = int(step_text)
            except ValueError as exc:
                raise CronError(f"步长非法: {step_text}") from exc
            if step <= 0:
                raise CronError("步长必须是正整数")
        if part in {"*", "?"}:
            start, end = minimum, maximum
        elif "-" in part:
            start_text, _, end_text = part.partition("-")
            try:
                start, end = int(start_text), int(end_text)
            except ValueError as exc:
                raise CronError(f"区间非法: {part}") from exc
        else:
            try:
                start = end = int(part)
            except ValueError as exc:
                raise CronError(f"字段必须是数字: {part}") from exc
        if start < minimum or end > maximum or start > end:
            raise CronError(f"字段超出范围 [{minimum}, {maximum}]: {part}")
        values.update(range(start, end + 1, step))
    if not values:
        raise CronError("Cron 字段没有可用取值")
    return values


@dataclass(frozen=True)
class CronSpec:
    seconds: frozenset[int]
    minutes: frozenset[int]
    hours: frozenset[int]
    days: frozenset[int]
    months: frozenset[int]
    weekdays: frozenset[int]

    def matches(self, moment: datetime) -> bool:
        # Quartz 语义：1=周日 … 7=周六
        weekday = (moment.isoweekday() % 7) + 1
        return (
            moment.second in self.seconds
            and moment.minute in self.minutes
            and moment.hour in self.hours
            and moment.day in self.days
            and moment.month in self.months
            and weekday in self.weekdays
        )


def parse_cron(expression: str) -> CronSpec:
    """解析 5 或 6 字段 Cron；字段数不对或取值非法直接报错。"""
    parts = str(expression or "").split()
    if len(parts) == 5:
        parts = ["0", *parts]
    if len(parts) != 6:
        raise CronError("Cron 表达式必须是 5 或 6 字段")
    return CronSpec(
        seconds=frozenset(_parse_field(parts[0], 0, 59)),
        minutes=frozenset(_parse_field(parts[1], 0, 59)),
        hours=frozenset(_parse_field(parts[2], 0, 23)),
        days=frozenset(_parse_field(parts[3], 1, 31)),
        months=frozenset(_parse_field(parts[4], 1, 12)),
        weekdays=frozenset(_parse_field(parts[5], 1, 7)),
    )


@dataclass
class ScheduleEntry:
    workflow_version_id: int
    cron: str
    spec: CronSpec
    node_key: str = "system.schedule"


class ScheduleRegistry:
    """Java 推送的定时触发快照；到点直接写工作流任务流。"""

    def __init__(self, bus: RedisWorkflowBus) -> None:
        self.bus = bus
        self.entries: dict[int, ScheduleEntry] = {}
        self._last_fired: dict[int, str] = {}
        self._task: asyncio.Task[Any] | None = None

    def replace(self, schedules: list[dict[str, Any]]) -> int:
        """整份替换：Java 是调度真相，这里只做求值和投递。"""
        entries: dict[int, ScheduleEntry] = {}
        for item in schedules or []:
            if not isinstance(item, dict):
                continue
            try:
                version_id = int(item.get("workflowVersionId") or 0)
            except (TypeError, ValueError):
                continue
            expression = str(item.get("cron") or "").strip()
            if version_id <= 0 or not expression:
                continue
            try:
                spec = parse_cron(expression)
            except CronError as exc:
                log.warning("跳过非法 Cron: version=%s cron=%s error=%s", version_id, expression, exc)
                continue
            entries[version_id] = ScheduleEntry(
                workflow_version_id=version_id,
                cron=expression,
                spec=spec,
                node_key=str(item.get("nodeKey") or "system.schedule"),
            )
        self.entries = entries
        self._last_fired = {
            key: value for key, value in self._last_fired.items() if key in entries
        }
        log.info("定时触发快照已更新: %s 条", len(entries))
        return len(entries)

    def start(self) -> None:
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._loop())

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            self._task = None

    async def _loop(self) -> None:
        while True:
            try:
                await self._tick()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - 调度循环不能因为单次失败退出
                log.exception("定时触发求值失败")
            await asyncio.sleep(1)

    async def _tick(self) -> None:
        moment = datetime.now()
        stamp = moment.strftime("%Y%m%d%H%M%S")
        for entry in list(self.entries.values()):
            if not entry.spec.matches(moment):
                continue
            if self._last_fired.get(entry.workflow_version_id) == stamp:
                continue
            self._last_fired[entry.workflow_version_id] = stamp
            try:
                await self.bus.publish_scheduled_task(
                    workflow_version_id=entry.workflow_version_id,
                    node_key=entry.node_key,
                    event={"triggerTime": int(time.time() * 1000)},
                )
                log.info(
                    "定时触发已投递: version=%s cron=%s",
                    entry.workflow_version_id,
                    entry.cron,
                )
            except Exception:  # noqa: BLE001 - 单条失败不影响其他调度
                log.exception("定时触发投递失败: version=%s", entry.workflow_version_id)
