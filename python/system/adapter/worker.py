from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
from pathlib import Path
from typing import Any

from system.adapter.models import DesiredConnection
from system.adapter.protocol import MAX_MESSAGE_BYTES, message, require_message
from system.adapter.worker_runtime import WorkerRuntime

logging.basicConfig(stream=sys.stderr, level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("adapter-worker")


class Worker:
    def __init__(self, plugin_dir: Path, entry_point: str, adapter_type: str) -> None:
        self.output_lock = asyncio.Lock()
        self.runtime: WorkerRuntime | None = None
        self.plugin_dir, self.entry_point, self.adapter_type = plugin_dir, entry_point, adapter_type
        self.stop_requested = asyncio.Event()
        self.tasks: set[asyncio.Task[Any]] = set()

    async def send(self, value: dict[str, Any]) -> None:
        raw = (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
        if len(raw) > MAX_MESSAGE_BYTES:
            raise ValueError("进程间消息超过最大帧大小")
        async with self.output_lock:
            sys.stdout.buffer.write(raw)
            sys.stdout.buffer.flush()

    async def run(self) -> None:
        self.runtime = WorkerRuntime(self.plugin_dir, self.entry_point, self.adapter_type, self.send)
        await self.send(message("ready", workerPid=os.getpid(), adapterType=self.adapter_type))
        observation_task = asyncio.create_task(self._observation_loop())
        try:
            while not self.stop_requested.is_set():
                line = await asyncio.to_thread(sys.stdin.buffer.readline)
                if not line:
                    return
                if len(line) > MAX_MESSAGE_BYTES:
                    await self.send(message("error", errorCode="IPC_FAILED", errorMessage="命令超过最大帧大小"))
                    continue
                try:
                    command = require_message(json.loads(line))
                    await self.handle(command)
                except Exception as exc:
                    request_id = None
                    try:
                        request_id = json.loads(line).get("requestId")
                    except Exception:
                        pass
                    await self.send(message("reply", request_id=request_id, ok=False, errorCode="IPC_FAILED", errorMessage=str(exc)))
        finally:
            observation_task.cancel()

    async def _observation_loop(self) -> None:
        while True:
            await asyncio.sleep(1)
            if self.runtime is None:
                continue
            for ctx in self.runtime.connections.values():
                ctx.observation.observed_at = int(__import__("time").time() * 1000)
                await self.send(message("observation", **ctx.observation.as_dict()))

    async def handle(self, command: dict[str, Any]) -> None:
        assert self.runtime is not None
        kind, request_id = command["kind"], command.get("requestId")
        if kind == "shutdown":
            await self.send(message("reply", request_id=request_id, ok=True))
            self.stop_requested.set()
            return
        if kind == "apply":
            observation = await self.runtime.apply(DesiredConnection.from_dict(command["desired"]))
            await self.send(message("reply", request_id=request_id, ok=True, observation=observation.as_dict()))
        elif kind == "remove":
            observation = await self.runtime.remove(int(command["connectionId"]), str(command.get("reason", "删除期望")))
            await self.send(message("reply", request_id=request_id, ok=True, observation=None if observation is None else observation.as_dict()))
        elif kind == "status":
            ctx = self.runtime.connections.get(int(command["connectionId"]))
            await self.send(message("reply", request_id=request_id, ok=True, observation=None if not ctx else ctx.observation.as_dict()))
        elif kind == "invoke":
            # 动作要等平台回响应，而响应帧同样走这条 stdin 管道送进来。
            # 在这里直接 await 会把命令循环堵死，平台回的帧永远读不到，只能等到超时。
            task = asyncio.create_task(self._invoke_and_reply(request_id, command))
            self.tasks.add(task)
            task.add_done_callback(self.tasks.discard)
        elif kind == "session.open":
            await self.runtime.open_session(int(command["connectionId"]), str(command["sessionId"]), dict(command.get("metadata") or {}))
            await self.send(message("reply", request_id=request_id, ok=True))
        elif kind == "session.frame":
            frame = command.get("frame")
            if isinstance(frame, dict) and frame.get("encoding") == "base64":
                import base64
                frame = base64.b64decode(frame["data"])
            await self.runtime.feed_session(int(command["connectionId"]), str(command["sessionId"]), frame)
            await self.send(message("reply", request_id=request_id, ok=True))
        elif kind == "session.close":
            await self.runtime.close_session(int(command["connectionId"]), str(command["sessionId"]))
            await self.send(message("reply", request_id=request_id, ok=True))
        else:
            raise ValueError(f"未知工作进程命令: {kind}")

    async def _invoke_and_reply(self, request_id: str | None, command: dict[str, Any]) -> None:
        """后台执行动作并回包，保证命令循环能继续读 `session.frame`。"""
        assert self.runtime is not None
        action = str(command.get("action") or "")
        try:
            result = await self.runtime.invoke(
                int(command["connectionId"]), action, dict(command.get("params") or {})
            )
        except Exception as exc:  # noqa: BLE001 - 失败也必须回包，否则调用方只能等到超时
            # 回给调用方的只有一行消息，真正的定位信息（哪一行、哪个依赖抛的）只有这里能留下。
            # 不打这一行，插件动作的堆栈就彻底丢了。
            log.exception(
                "插件动作执行失败: connection=%s action=%s",
                command.get("connectionId"),
                action,
            )
            message_text = str(exc).strip() or type(exc).__name__
            error_type = type(exc).__name__
            # 平台/依赖只给了消息时把异常类型带上：光看「系统繁忙，请稍后重试」分不出
            # 是平台返回的、还是插件自己抛的。
            if error_type and error_type not in message_text:
                message_text = f"{message_text}（{error_type}）"
            await self.send(
                message(
                    "reply",
                    request_id=request_id,
                    ok=False,
                    # 插件自己带了分类（WorkflowError.code 之类）就用它，
                    # 否则才是兜底的 ACTION_FAILED。
                    errorCode=str(getattr(exc, "code", "") or "ACTION_FAILED"),
                    errorMessage=message_text,
                    errorType=error_type,
                )
            )
            return
        await self.send(message("reply", request_id=request_id, ok=True, result=result))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plugin-dir", required=True)
    parser.add_argument("--entry-point", default="main.py")
    parser.add_argument("--adapter-type", required=True)
    args = parser.parse_args()
    asyncio.run(Worker(Path(args.plugin_dir), args.entry_point, args.adapter_type).run())


if __name__ == "__main__":
    main()
