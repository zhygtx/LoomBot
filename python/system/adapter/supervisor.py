from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import uuid
from pathlib import Path
from typing import Any, Awaitable, Callable

from system.adapter.event_ingress import EventIngress
from system.adapter.models import ConnectionObservation, DesiredConnection
from system.adapter.protocol import MAX_MESSAGE_BYTES, message

log = logging.getLogger("adapter-supervisor")

# 动作调用的 IPC 等待上限（秒）。必须比 worker 的动作超时更长，见 worker_runtime
# 里那条超时阶梯：插件 echo 30s < worker 35s < 本值 45s < executor 55s < 前端 90s。
INVOKE_TIMEOUT_SECONDS = 45.0


def _needs_restart(current: DesiredConnection, desired: DesiredConnection) -> bool:
    """插件代码或解释器换了就得重启工作进程。

    同一个版本目录被原地覆盖时 `plugin_path` 不变，只看路径会一直跑旧代码，所以还要比
    制品哈希；解释器（私有 venv 装没装出来）也可能在两次同步之间变化。
    """
    return (
        current.plugin_path != desired.plugin_path
        or current.python_path != desired.python_path
        or current.artifact_sha256 != desired.artifact_sha256
    )


class WorkerHandle:
    def __init__(self, desired: DesiredConnection, python_command: str, event_ingress: EventIngress,
                 event_callback: Callable[[dict[str, Any]], Awaitable[None]]) -> None:
        self.desired = desired
        self.python_command = python_command
        self.event_ingress = event_ingress
        self.event_callback = event_callback
        self.process: asyncio.subprocess.Process | None = None
        self.write_lock = asyncio.Lock()
        self.pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self.observations: dict[int, dict[str, Any]] = {}
        self.reader_task: asyncio.Task[Any] | None = None
        self.stderr_task: asyncio.Task[Any] | None = None
        self.ready = False

    @property
    def interpreter(self) -> str:
        """插件代码一律用它自己的私有 venv 解释器跑；没有依赖时退回宿主 Python。

        依赖隔离靠这一条：插件把包装进 `<pluginDir>/.venv`，这里就用那个解释器起进程，
        宿主环境永远不需要为插件装包。
        """
        return self.desired.python_path or self.python_command

    async def start(self) -> None:
        if self.process and self.process.returncode is None:
            return
        self.ready = False
        self.process = await asyncio.create_subprocess_exec(
            self.interpreter, "-m", "system.adapter.worker", "--plugin-dir", self.desired.plugin_path,
            "--entry-point", self.desired.entry_point, "--adapter-type", self.desired.connection_type,
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            cwd=str(Path(__file__).resolve().parents[2]),
            env={**os.environ, "PYTHONUNBUFFERED": "1"})
        self.reader_task = asyncio.create_task(self._read_loop())
        self.stderr_task = asyncio.create_task(self._stderr_loop())
        await self._wait_ready()

    async def stop(self) -> None:
        if not self.process:
            return
        try:
            await self.request("shutdown", timeout=2)
        except Exception:
            pass
        if self.process.returncode is None:
            self.process.terminate()
            try:
                await asyncio.wait_for(self.process.wait(), 3)
            except asyncio.TimeoutError:
                self.process.kill()
        for task in (self.reader_task, self.stderr_task):
            if task:
                task.cancel()

    async def request(self, kind: str, timeout: float = 30, **body: Any) -> dict[str, Any]:
        await self.start()
        request_id = uuid.uuid4().hex
        future = asyncio.get_running_loop().create_future()
        self.pending[request_id] = future
        raw = json.dumps(message(kind, request_id=request_id, **body), ensure_ascii=False, separators=(",", ":")).encode() + b"\n"
        if len(raw) > MAX_MESSAGE_BYTES:
            self.pending.pop(request_id, None)
            raise ValueError("命令超过最大帧大小")
        try:
            async with self.write_lock:
                assert self.process and self.process.stdin
                self.process.stdin.write(raw)
                await self.process.stdin.drain()
            return await asyncio.wait_for(future, timeout)
        finally:
            self.pending.pop(request_id, None)

    async def _wait_ready(self) -> None:
        for _ in range(100):
            if self.process and self.process.returncode is not None:
                raise RuntimeError("插件工作进程提前退出")
            if getattr(self, "ready", False):
                return
            await asyncio.sleep(0.01)
        raise TimeoutError("插件工作进程未在限定时间内就绪")

    async def _read_loop(self) -> None:
        assert self.process and self.process.stdout
        try:
            async for line in self.process.stdout:
                if len(line) > MAX_MESSAGE_BYTES:
                    continue
                try:
                    value = json.loads(line)
                    kind = value.get("kind")
                    if kind == "ready":
                        self.ready = True
                    elif kind == "reply":
                        future = self.pending.get(value.get("requestId"))
                        if future and not future.done():
                            if value.get("ok", False):
                                future.set_result(value)
                            else:
                                future.set_exception(RuntimeError(value.get("errorMessage", "工作进程命令失败")))
                    elif kind == "observation":
                        self.observations[int(value["connectionId"])] = value
                    elif kind in {"event", "session.send", "session.close"}:
                        await self.event_callback(value)
                except Exception:
                    log.exception("解析插件工作进程消息失败")
        finally:
            error = RuntimeError("插件工作进程已退出")
            for future in self.pending.values():
                if not future.done():
                    future.set_exception(error)

    async def _stderr_loop(self) -> None:
        assert self.process and self.process.stderr
        async for line in self.process.stderr:
            log.info("[worker:%s] %s", self.desired.plugin_key, line.decode(errors="replace").rstrip())


class AdapterSupervisor:
    def __init__(self, config: Any, ingress: EventIngress) -> None:
        self.config, self.ingress = config, ingress
        self.workers: dict[tuple[int, str], WorkerHandle] = {}
        self.connection_workers: dict[int, WorkerHandle] = {}
        self.desired_connections: dict[int, DesiredConnection] = {}
        self.observations: dict[int, dict[str, Any]] = {}
        self.sessions: dict[str, tuple[int, asyncio.Queue[Any]]] = {}
        self.endpoints: dict[str, int] = {}
        self.connection_endpoints: dict[int, str] = {}
        # 事件发布不能阻塞工作进程消息读取：Redis 慢或不可用时，
        # 观测消息仍必须继续处理，否则前端会一直停在旧状态。
        self.event_queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue(maxsize=1024)
        self.event_task: asyncio.Task[Any] | None = None

    async def reconcile(self, desired: list[DesiredConnection]) -> list[dict[str, Any]]:
        wanted = {item.connection_id: item for item in desired}
        for connection_id in set(self.connection_workers) - set(wanted):
            await self.remove(connection_id)
        result = []
        for item in wanted.values():
            result.append(await self.apply(item))
        return result

    async def apply(self, desired: DesiredConnection) -> dict[str, Any]:
        worker_key = (desired.plugin_version_id, desired.connection_type)
        worker = self.workers.get(worker_key)
        if not worker:
            worker = WorkerHandle(desired, self.config.python_command, self.ingress, self._on_worker_message)
            self.workers[worker_key] = worker
        elif _needs_restart(worker.desired, desired):
            await worker.stop()
            worker.desired = desired
        self.connection_workers[desired.connection_id] = worker
        self.desired_connections[desired.connection_id] = desired
        old_endpoint = self.connection_endpoints.get(desired.connection_id)
        if old_endpoint and old_endpoint != desired.endpoint_path:
            self.endpoints.pop(old_endpoint, None)
        if desired.endpoint_path:
            self.endpoints[desired.endpoint_path] = desired.connection_id
            self.connection_endpoints[desired.connection_id] = desired.endpoint_path
        else:
            self.connection_endpoints.pop(desired.connection_id, None)
        try:
            response = await worker.request("apply", desired=desired.as_dict(), pluginPath=desired.plugin_path, entryPoint=desired.entry_point)
            observation = response.get("observation") or {"connectionId": desired.connection_id, "observedRevision": desired.revision, "state": "DISABLED"}
        except Exception as exc:
            observation = {"connectionId": desired.connection_id, "observedRevision": desired.revision, "state": "FAILED", "errorCode": "PLUGIN_LOAD_FAILED", "errorMessage": str(exc)}
        self.observations[desired.connection_id] = observation
        return observation

    async def remove(self, connection_id: int) -> dict[str, Any] | None:
        worker = self.connection_workers.pop(connection_id, None)
        self.desired_connections.pop(connection_id, None)
        endpoint = self.connection_endpoints.pop(connection_id, None)
        if endpoint:
            self.endpoints.pop(endpoint, None)
        if not worker:
            return self.observations.pop(connection_id, None)
        worker.observations.pop(connection_id, None)
        try:
            response = await worker.request("remove", connectionId=connection_id, reason="删除连接")
            observation = response.get("observation")
        except Exception as exc:
            observation = {"connectionId": connection_id, "state": "FAILED", "errorCode": "IPC_FAILED", "errorMessage": str(exc)}
        self.observations.pop(connection_id, None)
        return observation

    def endpoint_connection(self, endpoint: str) -> int | None:
        return self.endpoints.get(endpoint)

    async def invoke(self, connection_id: int, action: str, params: dict[str, Any]) -> Any:
        worker = self.connection_workers.get(connection_id)
        if not worker:
            raise RuntimeError("连接没有对应的插件工作进程")
        # 必须比 worker 的动作超时（ACTION_TIMEOUT_SECONDS）长，留出 IPC 往返；
        # 用默认的 30 秒会和动作超时同时触发，随机丢掉插件那条更准确的错误信息。
        response = await worker.request(
            "invoke",
            timeout=INVOKE_TIMEOUT_SECONDS,
            connectionId=connection_id,
            action=action,
            params=params,
        )
        return response.get("result")

    def status(self, connection_id: int) -> dict[str, Any] | None:
        for worker in self.workers.values():
            observation = worker.observations.get(connection_id)
            if observation is not None:
                return observation
        return self.observations.get(connection_id)

    def statuses(self, connection_ids: list[int] | None = None) -> list[dict[str, Any]]:
        # worker 每秒上报的观测才是运行中的真实状态；supervisor.observations
        # 只保存 apply/reconcile 返回的初始快照，必须让 worker 的观测覆盖它。
        merged = dict(self.observations)
        for worker in self.workers.values():
            merged.update(worker.observations)
        values = merged.values() if connection_ids is None else (merged.get(i) for i in connection_ids)
        return [value for value in values if value is not None]

    async def open_session(self, connection_id: int, metadata: dict[str, Any] | None = None) -> tuple[str, asyncio.Queue[Any]]:
        worker = self.connection_workers.get(connection_id)
        if not worker:
            raise RuntimeError("连接未启用")
        session_id = uuid.uuid4().hex
        queue: asyncio.Queue[Any] = asyncio.Queue(maxsize=256)
        self.sessions[session_id] = (connection_id, queue)
        try:
            await worker.request("session.open", connectionId=connection_id, sessionId=session_id, metadata=metadata or {})
        except Exception:
            self.sessions.pop(session_id, None)
            raise
        log.info("反向会话已建立: connection=%s session=%s", connection_id, session_id)
        return session_id, queue

    async def feed_session(self, session_id: str, frame: str | bytes) -> None:
        item = self.sessions.get(session_id)
        if not item:
            raise RuntimeError("会话不存在")
        connection_id, _ = item
        worker = self.connection_workers[connection_id]
        value: Any = frame
        if isinstance(frame, bytes):
            value = {"encoding": "base64", "data": base64.b64encode(frame).decode("ascii")}
        await worker.request("session.frame", connectionId=connection_id, sessionId=session_id, frame=value)

    async def close_session(self, session_id: str) -> None:
        item = self.sessions.pop(session_id, None)
        if not item:
            return
        connection_id, queue = item
        worker = self.connection_workers.get(connection_id)
        if worker:
            await worker.request("session.close", connectionId=connection_id, sessionId=session_id)
        await queue.put(None)
        log.info("反向会话已关闭: connection=%s session=%s", connection_id, session_id)

    async def _on_worker_message(self, value: dict[str, Any]) -> None:
        kind = value.get("kind")
        if kind == "event":
            self._enqueue_event(value)
        elif kind in {"session.send", "session.close"}:
            session = self.sessions.get(str(value.get("sessionId")))
            if session:
                _, queue = session
                await queue.put(None if kind == "session.close" else value.get("frame"))

    def _enqueue_event(self, value: dict[str, Any]) -> None:
        if self.event_task is None or self.event_task.done():
            self.event_task = asyncio.create_task(self._event_publish_loop())
        try:
            self.event_queue.put_nowait(value)
        except asyncio.QueueFull:
            log.error("事件发布队列已满，丢弃事件: nodeKey=%s", value.get("nodeKey"))

    async def _event_publish_loop(self) -> None:
        while True:
            value = await self.event_queue.get()
            try:
                if value is None:
                    return
                await self._publish_event(value)
            except Exception:
                log.exception(
                    "发布插件事件失败: nodeKey=%s",
                    value.get("nodeKey") if value else None,
                )
            finally:
                self.event_queue.task_done()

    async def _publish_event(self, value: dict[str, Any]) -> None:
        connection_id = int(value["connectionId"])
        worker = self.connection_workers.get(connection_id)
        desired = self.desired_connections.get(connection_id)
        if not worker or not desired:
            return
        try:
            envelope = await self.ingress.publish(
                connection_id=connection_id,
                revision=desired.revision,
                connection_type=desired.connection_type,
                plugin_version_id=desired.plugin_version_id,
                node_key=value["nodeKey"],
                payload=value.get("payload") or {},
                allowed_nodes=set(value.get("allowedNodes") or []),
            )
            if envelope.get("workflowVersionIds"):
                log.info(
                    "事件已投递工作流: connection=%s node=%s workflows=%s",
                    connection_id,
                    value["nodeKey"],
                    envelope["workflowVersionIds"],
                )
        except ValueError:
            log.warning("插件发出了未声明节点: %s", value.get("nodeKey"))

    async def stop(self) -> None:
        if self.event_task:
            self.event_task.cancel()
            self.event_task = None
        for worker in list(self.workers.values()):
            await worker.stop()
        self.workers.clear(); self.connection_workers.clear(); self.desired_connections.clear(); self.observations.clear(); self.sessions.clear(); self.endpoints.clear(); self.connection_endpoints.clear()
