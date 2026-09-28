from __future__ import annotations

import asyncio
import importlib.util
import inspect
import json
import logging
import time
import urllib.parse
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable

import httpx
import websockets

from adapter_host.models import ConnectionObservation, DesiredConnection

log = logging.getLogger("adapter-worker")


async def maybe_await(value: Any) -> Any:
    return await value if inspect.isawaitable(value) else value


class Session:
    def __init__(self, session_id: str, send_frame: Callable[[str, Any], Awaitable[None]], close_session: Callable[[str], Awaitable[None]], metadata: dict[str, Any] | None = None) -> None:
        self.session_id = session_id
        self._send_frame = send_frame
        self._close_session = close_session
        self.metadata = metadata or {}
        self._incoming: asyncio.Queue[str | bytes | None] = asyncio.Queue(maxsize=256)

    async def receive(self) -> str | bytes | None:
        return await self._incoming.get()

    def feed(self, frame: str | bytes | None) -> None:
        try:
            self._incoming.put_nowait(frame)
        except asyncio.QueueFull:
            raise RuntimeError("反向会话接收队列已满")

    async def send(self, frame: str | bytes) -> None:
        await self._send_frame(self.session_id, frame)

    async def close(self, reason: str = "") -> None:
        await self._close_session(self.session_id)


@dataclass(slots=True)
class WorkerConnectionContext:
    desired: DesiredConnection
    observation: ConnectionObservation
    emit_callback: Callable[[str, dict[str, Any]], Awaitable[None]]
    send_frame_callback: Callable[[str, Any], Awaitable[None]]
    close_session_callback: Callable[[str], Awaitable[None]]
    cancel_event: asyncio.Event = field(default_factory=asyncio.Event)
    sessions: dict[str, Session] = field(default_factory=dict)

    @property
    def connection_id(self) -> int:
        return self.desired.connection_id

    @property
    def revision(self) -> int:
        return self.desired.revision

    @property
    def config(self) -> dict[str, Any]:
        return self.desired.config

    @property
    def endpoint_path(self) -> str | None:
        return self.desired.endpoint_path

    async def emit_node(self, node_key: str, payload: dict[str, Any]) -> None:
        await self.emit_callback(node_key, payload)

    async def connect_forward(self, url: str, headers: dict[str, str] | None = None) -> Any:
        params = inspect.signature(websockets.connect).parameters
        kwargs = {"ping_interval": None}
        if "additional_headers" in params:
            kwargs["additional_headers"] = headers or {}
        else:
            kwargs["extra_headers"] = headers or {}
        return await websockets.connect(url, **kwargs)

    async def http_json(self, method: str, url: str, *, headers: dict[str, str] | None = None,
                        json_body: dict[str, Any] | None = None) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.request(method, url, headers=headers, json=json_body)
            response.raise_for_status()
            value = response.json()
            if not isinstance(value, dict):
                raise ValueError("HTTP 响应必须是 JSON 对象")
            return value

    def set_state(self, state: str, *, code: str | None = None, message: str | None = None) -> None:
        self.observation.state = state
        self.observation.error_code = code
        self.observation.error_message = message


class AdapterHostApi:
    def __init__(self, manifest: dict[str, Any]) -> None:
        self.manifest = manifest


class WorkerRuntime:
    def __init__(self, plugin_dir: Path, entry_point: str, adapter_type: str, send: Callable[[dict[str, Any]], Awaitable[None]]) -> None:
        self.plugin_dir = plugin_dir.resolve()
        self.adapter_type = adapter_type
        self.send = send
        self.connections: dict[int, WorkerConnectionContext] = {}
        self.session_tasks: dict[tuple[int, str], asyncio.Task[Any]] = {}
        self.allowed_nodes = self._load_nodes()
        self.adapter = self._load_adapter(entry_point)

    def _load_nodes(self) -> set[str]:
        manifest = self._read_manifest()
        adapter = manifest.get("adapter") or {}
        declared_adapters = manifest.get("adapters") or []
        if isinstance(declared_adapters, list):
            for candidate in declared_adapters:
                if isinstance(candidate, dict) and str(candidate.get("type") or candidate.get("connection_type") or "") == self.adapter_type:
                    adapter = candidate
                    break
        prefix = str(adapter.get("node_prefix") or "").strip(".")
        result: set[str] = set()
        for raw in adapter.get("trigger_nodes") or []:
            node = str(raw).strip()
            result.add(node if not prefix or node.startswith(prefix + ".") or node.isupper() else f"{prefix}.{node}")
        return result

    def _read_manifest(self) -> dict[str, Any]:
        try:
            import tomllib
            with (self.plugin_dir / "plugin.toml").open("rb") as stream:
                return tomllib.load(stream)
        except Exception:
            return {}

    def _load_adapter(self, entry_point: str) -> Any:
        path = (self.plugin_dir / entry_point).resolve()
        if not path.is_relative_to(self.plugin_dir) or not path.is_file():
            raise ValueError(f"插件入口不在插件目录内: {entry_point}")
        spec = importlib.util.spec_from_file_location(f"loom_plugin_{self.plugin_dir.name}", path)
        if spec is None or spec.loader is None:
            raise ImportError(f"无法加载插件入口: {path}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        factory = getattr(module, "create_adapter", None)
        if factory is None:
            raise ImportError("插件必须导出 create_adapter(host)")
        return factory(AdapterHostApi(self._read_manifest()))

    async def apply(self, desired: DesiredConnection) -> ConnectionObservation:
        current = self.connections.get(desired.connection_id)
        if current and desired.revision < current.revision:
            return current.observation
        if current and desired.revision == current.revision and desired.config == current.desired.config and desired.enabled == current.desired.enabled:
            return current.observation
        if current:
            await self.remove(desired.connection_id, "配置更新")
        observation = ConnectionObservation(desired.connection_id, desired.revision, "DISABLED" if not desired.enabled else "STARTING", worker_pid=None, observed_at=int(time.time() * 1000))
        ctx = WorkerConnectionContext(desired, observation, self._emit(desired.connection_id), self._session_send, self._session_close)
        self.connections[desired.connection_id] = ctx
        if not desired.enabled:
            return observation
        try:
            if desired.direction == "REVERSE":
                observation.state = "LISTENING"
            else:
                observation.state = "CONNECTING"
            await maybe_await(self.adapter.start(ctx))
            observation.observed_at = int(time.time() * 1000)
        except Exception as exc:
            observation.state, observation.error_code, observation.error_message = "FAILED", "CONFIG_INVALID", str(exc)
            log.exception("插件启动失败: connection=%s", desired.connection_id)
        return observation

    async def remove(self, connection_id: int, reason: str) -> ConnectionObservation | None:
        ctx = self.connections.pop(connection_id, None)
        if not ctx:
            return None
        ctx.set_state("STOPPING", message=reason)
        ctx.cancel_event.set()
        for key, task in list(self.session_tasks.items()):
            if key[0] == connection_id:
                task.cancel()
                self.session_tasks.pop(key, None)
        try:
            await asyncio.wait_for(maybe_await(self.adapter.stop(ctx, reason)), timeout=10)
        except Exception:
            log.exception("插件停止失败: connection=%s", connection_id)
        ctx.observation.state = "DISABLED"
        ctx.observation.observed_at = int(time.time() * 1000)
        return ctx.observation

    async def invoke(self, connection_id: int, action: str, params: dict[str, Any]) -> Any:
        ctx = self.connections.get(connection_id)
        if not ctx:
            raise RuntimeError("连接未加载")
        return await asyncio.wait_for(maybe_await(self.adapter.invoke(ctx, action, params)), timeout=30)

    async def open_session(self, connection_id: int, session_id: str, metadata: dict[str, Any] | None = None) -> None:
        ctx = self.connections.get(connection_id)
        if not ctx or ctx.desired.direction != "REVERSE":
            raise RuntimeError("反向连接未启用")
        session = Session(session_id, self._session_send, self._session_close, metadata)
        ctx.sessions[session_id] = session
        ctx.observation.state = "ONLINE"
        log.info("插件反向会话进入 ONLINE: connection=%s session=%s", connection_id, session_id)
        task = asyncio.create_task(maybe_await(self.adapter.on_session(ctx, session)))
        self.session_tasks[(connection_id, session_id)] = task
        def finished(done: asyncio.Task[Any]) -> None:
            ctx.sessions.pop(session_id, None)
            self.session_tasks.pop((connection_id, session_id), None)
            log.info("插件反向会话已结束: connection=%s session=%s", connection_id, session_id)
            if not done.cancelled() and done.exception() is not None:
                exc = done.exception()
                log.error("插件反向会话处理失败: connection=%s session=%s", connection_id, session_id,
                          exc_info=(type(exc), exc, exc.__traceback__))
            if ctx.desired.enabled and not ctx.sessions:
                ctx.observation.state = "LISTENING"
            # 只要会话任务结束，就必须让网关关闭底层 WebSocket。
            # 否则插件如果直接 return 或抛异常、没有显式调用 session.close，
            # 平台侧会一直挂着一个未认证或已失效的连接。
            asyncio.create_task(self._session_close(session_id))
        task.add_done_callback(finished)

    async def feed_session(self, connection_id: int, session_id: str, frame: str | bytes) -> None:
        ctx = self.connections.get(connection_id)
        if not ctx or session_id not in ctx.sessions:
            raise RuntimeError("会话不存在")
        ctx.sessions[session_id].feed(frame)
        ctx.observation.last_received_at = int(time.time() * 1000)

    async def close_session(self, connection_id: int, session_id: str) -> None:
        ctx = self.connections.get(connection_id)
        if not ctx:
            return
        log.info("插件反向会话收到关闭请求: connection=%s session=%s", connection_id, session_id)
        session = ctx.sessions.pop(session_id, None)
        if session:
            session.feed(None)
        task = self.session_tasks.pop((connection_id, session_id), None)
        if task:
            task.cancel()
        if not ctx.sessions and ctx.desired.enabled:
            ctx.observation.state = "LISTENING"

    def _emit(self, connection_id: int) -> Callable[[str, dict[str, Any]], Awaitable[None]]:
        async def emit(node_key: str, payload: dict[str, Any]) -> None:
            await self.send({"kind": "event", "connectionId": connection_id, "nodeKey": node_key, "payload": payload, "allowedNodes": sorted(self.allowed_nodes)})
        return emit

    async def _session_send(self, session_id: str, frame: Any) -> None:
        await self.send({"kind": "session.send", "sessionId": session_id, "frame": frame})

    async def _session_close(self, session_id: str) -> None:
        await self.send({"kind": "session.close", "sessionId": session_id})
