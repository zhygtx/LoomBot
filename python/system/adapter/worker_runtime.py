"""插件工作进程的适配器运行时。

事件按注册表分发，动作按节点键调用；插件只写函数，不写分发代码。
"""

from __future__ import annotations

import asyncio
import importlib.util
import inspect
import json
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable

import httpx
import websockets

from system.adapter.models import ConnectionObservation, DesiredConnection
from system.scanner.adapters import scan_adapter_specs
from system.scanner.manifest import AdapterDecl, read_manifest

log = logging.getLogger("adapter-worker")


def decode_frame(frame: Any) -> dict[str, Any] | None:
    """把平台帧解码成对象；不是对象就返回 None，由调用方丢弃。"""
    if isinstance(frame, (bytes, bytearray)):
        frame = bytes(frame).decode("utf-8", errors="replace")
    if isinstance(frame, str):
        try:
            frame = json.loads(frame)
        except (TypeError, ValueError):
            return None
    return frame if isinstance(frame, dict) else None


class Session:
    """一条反向平台会话。"""

    def __init__(
        self,
        session_id: str,
        send_frame: Callable[[str, Any], Awaitable[None]],
        close_session: Callable[[str], Awaitable[None]],
        metadata: dict[str, Any] | None = None,
    ) -> None:
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
        except asyncio.QueueFull as exc:
            raise RuntimeError("反向会话接收队列已满") from exc

    async def send(self, frame: str | bytes) -> None:
        await self._send_frame(self.session_id, frame)

    async def close(self, reason: str = "") -> None:
        await self._close_session(self.session_id)


@dataclass(slots=True)
class ConnectionContext:
    """插件看到的连接句柄。"""

    desired: DesiredConnection
    observation: ConnectionObservation
    registry: "AdapterRegistry"
    emit_callback: Callable[[str, dict[str, Any]], Awaitable[None]]
    send_frame_callback: Callable[[str, Any], Awaitable[None]]
    close_session_callback: Callable[[str], Awaitable[None]]
    cancel_event: asyncio.Event = field(default_factory=asyncio.Event)
    sessions: dict[str, Session] = field(default_factory=dict)
    session: Session | None = None
    state: dict[str, Any] = field(default_factory=dict)

    @property
    def connection_id(self) -> int:
        return self.desired.connection_id

    @property
    def revision(self) -> int:
        return self.desired.revision

    @property
    def adapter_type(self) -> str:
        return self.desired.connection_type

    @property
    def plugin_key(self) -> str:
        return self.desired.plugin_key

    @property
    def plugin_version_id(self) -> int:
        return self.desired.plugin_version_id

    @property
    def config(self) -> dict[str, Any]:
        return self.desired.config

    @property
    def endpoint_path(self) -> str | None:
        return self.desired.endpoint_path

    def current_state(self) -> str:
        return self.observation.state

    def set_state(self, state: str, *, code: str | None = None, message: str | None = None) -> None:
        self.observation.state = state
        self.observation.error_code = code
        self.observation.error_message = message
        self.observation.observed_at = int(time.time() * 1000)

    async def dispatch(self, frame: Any) -> list[str]:
        """把一帧交给全部事件函数，返回命中的节点键。"""
        value = decode_frame(frame)
        if value is None:
            return []
        hits: list[str] = []
        for spec in self.registry.events:
            try:
                payload = await spec.func(self, value)
            except Exception as exc:  # noqa: BLE001 - 单个事件函数失败不影响其余函数
                log.exception("事件函数执行失败: nodeKey=%s connection=%s", spec.key, self.connection_id)
                self.set_state(self.current_state(), code="PLUGIN_ERROR", message=str(exc))
                continue
            if payload is None:
                continue
            if not isinstance(payload, dict):
                log.error(
                    "事件函数必须返回字典或 None: nodeKey=%s 返回=%s",
                    spec.key,
                    type(payload).__name__,
                )
                continue
            await self.emit_node(spec.key, payload)
            hits.append(spec.key)
        return hits

    async def emit_node(self, node_key: str, payload: dict[str, Any]) -> None:
        """直接发射一个声明过的节点事件。"""
        await self.emit_callback(node_key, payload)

    async def connect_forward(self, url: str, headers: dict[str, str] | None = None) -> Any:
        params = inspect.signature(websockets.connect).parameters
        kwargs: dict[str, Any] = {"ping_interval": None}
        if "additional_headers" in params:
            kwargs["additional_headers"] = headers or {}
        else:
            kwargs["extra_headers"] = headers or {}
        return await websockets.connect(url, **kwargs)

    async def http_json(
        self,
        method: str,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        json_body: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.request(method, url, headers=headers, json=json_body)
            response.raise_for_status()
            value = response.json()
            if not isinstance(value, dict):
                raise ValueError("HTTP 响应必须是 JSON 对象")
            return value


class AdapterHostApi:
    """传给 `create_adapter(host)` 的宿主句柄，当前只暴露清单。"""

    def __init__(self, manifest: Any) -> None:
        self.manifest = manifest


class AdapterRegistry:
    """一个适配器类型的注册表：事件列表与动作表。"""

    def __init__(self, plugin_dir: Path, adapter_type: str, entry_point: str | None = None) -> None:
        self.plugin_dir = plugin_dir.resolve()
        self.adapter_type = adapter_type
        manifest = read_manifest(self.plugin_dir)
        declaration = next(
            (item for item in manifest.adapters if item.adapter_type == adapter_type), None
        )
        if declaration is None:
            raise ValueError(f"插件没有声明适配器类型: {adapter_type}")
        self.declaration: AdapterDecl = declaration
        self.manifest = manifest
        events, actions = scan_adapter_specs(self.plugin_dir, declaration)
        self.events = events
        self.actions = {spec.key: spec for spec in actions}
        self.event_keys = {spec.key for spec in events}
        self.entry_point = entry_point or declaration.entry

    def load_adapter(self) -> Any:
        """加载类型入口并调用工厂函数。"""
        path = (self.plugin_dir / self.entry_point).resolve()
        if not path.is_relative_to(self.plugin_dir) or not path.is_file():
            raise ValueError(f"插件入口不在插件目录内: {self.entry_point}")
        spec = importlib.util.spec_from_file_location(
            f"loom_entry_{self.plugin_dir.name}", path
        )
        if spec is None or spec.loader is None:
            raise ImportError(f"无法加载插件入口: {path}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        factory = getattr(module, "create_adapter", None)
        if factory is None:
            raise ImportError("插件必须导出 create_adapter(host)")
        return factory(AdapterHostApi(self.manifest))

    async def invoke(self, ctx: ConnectionContext, action: str, params: dict[str, Any]) -> Any:
        spec = self.actions.get(action)
        if spec is None:
            raise RuntimeError(f"未知动作: {action}")
        return await spec.func(ctx, params)


class WorkerRuntime:
    """一个插件版本 + 适配器类型的工作进程运行时。"""

    def __init__(
        self,
        plugin_dir: Path,
        entry_point: str,
        adapter_type: str,
        send: Callable[[dict[str, Any]], Awaitable[None]],
    ) -> None:
        self.send = send
        self.registry = AdapterRegistry(plugin_dir, adapter_type, entry_point)
        self.adapter = self.registry.load_adapter()
        self.connections: dict[int, ConnectionContext] = {}
        self.session_tasks: dict[tuple[int, str], asyncio.Task[Any]] = {}
        self.has_session_hook = callable(getattr(self.adapter, "on_session", None))

    async def apply(self, desired: DesiredConnection) -> ConnectionObservation:
        current = self.connections.get(desired.connection_id)
        if current and desired.revision < current.revision:
            return current.observation
        if (
            current
            and desired.revision == current.revision
            and desired.config == current.desired.config
            and desired.enabled == current.desired.enabled
        ):
            return current.observation
        if current:
            await self.remove(desired.connection_id, "配置更新")
        observation = ConnectionObservation(
            desired.connection_id,
            desired.revision,
            "DISABLED" if not desired.enabled else "STARTING",
            worker_pid=None,
            observed_at=int(time.time() * 1000),
        )
        ctx = ConnectionContext(
            desired,
            observation,
            self.registry,
            self._emit(desired.connection_id),
            self._session_send,
            self._session_close,
        )
        self.connections[desired.connection_id] = ctx
        if not desired.enabled:
            return observation
        try:
            if desired.direction == "REVERSE":
                observation.state = "LISTENING"
            else:
                observation.state = "CONNECTING"
            start = getattr(self.adapter, "start", None)
            if start is not None:
                await start(ctx)
            observation.observed_at = int(time.time() * 1000)
        except Exception as exc:  # noqa: BLE001 - 启动失败写观测
            observation.state = "FAILED"
            observation.error_code = "CONFIG_INVALID"
            observation.error_message = str(exc)
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
        stop = getattr(self.adapter, "stop", None)
        if stop is not None:
            try:
                await asyncio.wait_for(stop(ctx, reason), timeout=10)
            except Exception:  # noqa: BLE001 - 停止失败只记日志
                log.exception("插件停止失败: connection=%s", connection_id)
        ctx.session = None
        ctx.sessions.clear()
        ctx.state.clear()
        ctx.set_state("DISABLED")
        return ctx.observation

    async def invoke(self, connection_id: int, action: str, params: dict[str, Any]) -> Any:
        ctx = self.connections.get(connection_id)
        if not ctx:
            raise RuntimeError("连接未加载")
        return await asyncio.wait_for(self.registry.invoke(ctx, action, params), timeout=30)

    async def open_session(
        self, connection_id: int, session_id: str, metadata: dict[str, Any] | None = None
    ) -> None:
        ctx = self.connections.get(connection_id)
        if not ctx or ctx.desired.direction != "REVERSE":
            raise RuntimeError("反向连接未启用")
        session = Session(session_id, self._session_send, self._session_close, metadata)
        ctx.sessions[session_id] = session
        ctx.session = session
        ctx.set_state("ONLINE")
        log.info("插件反向会话进入 ONLINE: connection=%s session=%s", connection_id, session_id)
        if self.has_session_hook:
            task = asyncio.create_task(self.adapter.on_session(ctx, session))
        else:
            task = asyncio.create_task(self._default_session_loop(ctx, session))
        self.session_tasks[(connection_id, session_id)] = task

        def finished(done: asyncio.Task[Any]) -> None:
            ctx.sessions.pop(session_id, None)
            self.session_tasks.pop((connection_id, session_id), None)
            log.info("插件反向会话已结束: connection=%s session=%s", connection_id, session_id)
            if not done.cancelled() and done.exception() is not None:
                exc = done.exception()
                log.error(
                    "插件反向会话处理失败: connection=%s session=%s",
                    connection_id,
                    session_id,
                    exc_info=(type(exc), exc, exc.__traceback__),
                )
            if ctx.session is session:
                ctx.session = None
            if ctx.desired.enabled and not ctx.sessions and ctx.current_state() == "ONLINE":
                ctx.set_state("LISTENING")
            # 只要会话任务结束，就必须让网关关闭底层 WebSocket，
            # 否则插件直接 return 或抛异常时，平台侧会一直挂着一条失效连接。
            asyncio.create_task(self._session_close(session_id))

        task.add_done_callback(finished)

    async def _default_session_loop(self, ctx: ConnectionContext, session: Session) -> None:
        """插件没有实现 on_session 时，由宿主收帧并分发。"""
        while True:
            frame = await session.receive()
            if frame is None:
                return
            await ctx.dispatch(frame)

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
        if ctx.session is session:
            ctx.session = None
        if not ctx.sessions and ctx.desired.enabled and ctx.current_state() == "ONLINE":
            ctx.set_state("LISTENING")

    def _emit(self, connection_id: int) -> Callable[[str, dict[str, Any]], Awaitable[None]]:
        async def emit(node_key: str, payload: dict[str, Any]) -> None:
            await self.send(
                {
                    "kind": "event",
                    "connectionId": connection_id,
                    "nodeKey": node_key,
                    "payload": payload,
                    "allowedNodes": sorted(self.registry.event_keys),
                }
            )

        return emit

    async def _session_send(self, session_id: str, frame: Any) -> None:
        await self.send({"kind": "session.send", "sessionId": session_id, "frame": frame})

    async def _session_close(self, session_id: str) -> None:
        await self.send({"kind": "session.close", "sessionId": session_id})
