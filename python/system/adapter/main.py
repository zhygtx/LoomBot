from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Request, WebSocket
import uvicorn

from system.adapter.config import HostConfig
from system.adapter.event_ingress import EventIngress
from system.adapter.gateway import WebSocketGateway
from system.adapter.models import DesiredConnection
from system.adapter.redis_bus import RedisWorkflowBus
from system.adapter.scheduler import ScheduleRegistry
from system.adapter.supervisor import AdapterSupervisor
from system.adapter.worker_runtime import ACTION_TIMEOUT_SECONDS

Path("logs").mkdir(exist_ok=True)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("logs/adapter-host.log", encoding="utf-8"),
    ],
)
log = logging.getLogger("adapter-control")


class _SkipControlPlanePollingAccessLog(logging.Filter):
    """丢掉 Java 控制面的轮询访问日志，只保留真正有价值的请求。

    控制面每 2 秒被轮询一次，如果照常记录访问日志会淹没连接相关的日志。
    """

    _POLLED_PATHS = (
        "/internal/health",
        "/internal/observations",
        "/internal/runtime",
        "/internal/schedules/snapshot",
    )

    def filter(self, record: logging.LogRecord) -> bool:
        args = record.args
        if isinstance(args, tuple) and len(args) >= 3:
            return not str(args[2]).startswith(self._POLLED_PATHS)
        return True


class _SkipWebSocketRejectionNoise(logging.Filter):
    """丢掉未知端点握手被拒时底层库连打的三行噪音。

    uvicorn 把 `uvicorn.error` 注入成 websockets 协议的 logger，所以
    `"WebSocket <path>" 403`、`connection failed (403 Forbidden)`、
    `connection closed` 三行都落在同一个 logger 上。前两行完全由 gateway 的
    WARN 取代；`connection closed` 只在拒绝之后丢掉，正常连接关闭照常保留。
    """

    def __init__(self) -> None:
        super().__init__()
        self._pending_rejections = 0

    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage().rstrip()
        if '"WebSocket' in message and message.endswith("403"):
            self._pending_rejections += 1
            return False
        if message.startswith("connection failed (403"):
            return False
        if message == "connection closed" and self._pending_rejections > 0:
            self._pending_rejections -= 1
            return False
        return True


def _take_over_uvicorn_logging() -> None:
    """统一接管 uvicorn 的日志，避免两个 Server 互相覆盖日志配置。

    控制面和网关在同一个进程里，uvicorn 默认的 log_config 会在每个 Server 启动时
    重置共享的 "uvicorn.access" logger，使控制面的 access_log=False 失效。
    这里不再让 uvicorn 接管日志，改用过滤器精确丢弃轮询请求。
    """

    access = logging.getLogger("uvicorn.access")
    access.setLevel(logging.INFO)
    if not any(isinstance(item, _SkipControlPlanePollingAccessLog) for item in access.filters):
        access.addFilter(_SkipControlPlanePollingAccessLog())
    error = logging.getLogger("uvicorn.error")
    error.setLevel(logging.INFO)
    if not any(isinstance(item, _SkipWebSocketRejectionNoise) for item in error.filters):
        error.addFilter(_SkipWebSocketRejectionNoise())


_take_over_uvicorn_logging()
config = HostConfig.from_env()
redis_bus = RedisWorkflowBus(config.redis_url, config.task_stream, config.index_prefix, config.task_ttl_seconds)
supervisor = AdapterSupervisor(config, EventIngress(redis_bus, config.event_audit))
scheduler = ScheduleRegistry(redis_bus)
gateway = WebSocketGateway(supervisor)


@asynccontextmanager
async def lifespan(_: FastAPI):
    scheduler.start()
    try:
        yield
    finally:
        await scheduler.stop()
        await supervisor.stop()
        await redis_bus.close()


app = FastAPI(title="LoomBot Adapter Supervisor", docs_url=None, redoc_url=None, lifespan=lifespan)
gateway_app = FastAPI(title="LoomBot Adapter Gateway", docs_url=None, redoc_url=None)


def require_token(token: str | None) -> None:
    if token != config.control_token:
        raise HTTPException(status_code=401, detail="invalid adapter control token")


@app.get("/internal/health")
async def health(x_adapter_token: str | None = Header(default=None)) -> dict[str, Any]:
    require_token(x_adapter_token)
    return {"ok": True, "protocolVersion": 1, "instanceId": f"supervisor-{id(supervisor):x}"}


@app.get("/internal/runtime")
async def runtime(x_adapter_token: str | None = Header(default=None)) -> dict[str, Any]:
    require_token(x_adapter_token)
    return {"ok": True, "workers": [{"pluginVersionId": key, "pid": worker.process.pid if worker.process else None} for key, worker in supervisor.workers.items()]}


@app.post("/internal/desired/apply")
async def apply(request: Request, x_adapter_token: str | None = Header(default=None)) -> dict[str, Any]:
    require_token(x_adapter_token)
    body = await request.json()
    try:
        desired = DesiredConnection.from_dict(body)
        if not desired.plugin_path:
            desired.plugin_path = str((config.plugin_root / desired.plugin_key).resolve())
        observation = await supervisor.apply(desired)
        return {"accepted": True, "observation": observation}
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/internal/desired/snapshot")
async def snapshot(request: Request, x_adapter_token: str | None = Header(default=None)) -> dict[str, Any]:
    require_token(x_adapter_token)
    body = await request.json()
    values = [DesiredConnection.from_dict(item) for item in body.get("connections", [])]
    for desired in values:
        if not desired.plugin_path:
            desired.plugin_path = str((config.plugin_root / desired.plugin_key).resolve())
    observations = await supervisor.reconcile(values)
    return {"accepted": True, "snapshotGeneration": body.get("snapshotGeneration"), "observations": observations}


@app.delete("/internal/desired/{connection_id}")
async def remove(connection_id: int, x_adapter_token: str | None = Header(default=None)) -> dict[str, Any]:
    require_token(x_adapter_token)
    return {"accepted": True, "observation": await supervisor.remove(connection_id)}


@app.get("/internal/observations")
async def observations(x_adapter_token: str | None = Header(default=None)) -> dict[str, Any]:
    require_token(x_adapter_token)
    return {"runtimeReachable": True, "observations": supervisor.statuses()}


@app.post("/internal/schedules/snapshot")
async def schedules(request: Request, x_adapter_token: str | None = Header(default=None)) -> dict[str, Any]:
    """Java 推送定时触发快照；适配器层负责 Cron 求值和任务投递。"""
    require_token(x_adapter_token)
    body = await request.json()
    count = scheduler.replace(list(body.get("schedules") or []))
    return {"accepted": True, "count": count}


@app.post("/internal/actions/{connection_id}")
async def action(connection_id: int, request: Request, x_adapter_token: str | None = Header(default=None)) -> dict[str, Any]:
    require_token(x_adapter_token)
    body = await request.json()
    try:
        return {"status": "SUCCEEDED", "result": await supervisor.invoke(connection_id, str(body.get("action")), dict(body.get("params") or {}))}
    except asyncio.TimeoutError:
        # 超时只说明没等到平台响应，动作本身可能已经生效，所以是 UNKNOWN 而不是 FAILED。
        return {
            "status": "UNKNOWN",
            "errorCode": "ACTION_TIMEOUT",
            "errorMessage": (
                f"平台未在 {ACTION_TIMEOUT_SECONDS:g} 秒内返回响应，动作可能已执行；"
                "请检查平台侧是否回传了带 echo 的响应"
            ),
        }
    except Exception as exc:
        return {"status": "FAILED", "errorCode": "ACTION_FAILED", "errorMessage": str(exc)}


# 同一个未知端点 5 分钟内只告警一次，避免失联的客户端把日志刷满。
_REJECT_LOG_INTERVAL_SECONDS = 300.0
_rejected_paths: dict[str, float] = {}


def _log_unknown_endpoint(path: str, client: Any) -> None:
    now = time.monotonic()
    last = _rejected_paths.get(path)
    if last is not None and now - last < _REJECT_LOG_INTERVAL_SECONDS:
        log.debug("再次拒绝未知端点的 WebSocket 连接: path=%s", path)
        return
    _rejected_paths[path] = now
    host = getattr(client, "host", None) or "未知来源"
    log.warning("拒绝未知端点的 WebSocket 连接: path=%s client=%s", path, host)


async def websocket_gateway(websocket: WebSocket, path: str) -> None:
    endpoint = "/" + path
    connection_id = supervisor.endpoint_connection(endpoint)
    if connection_id is None:
        _log_unknown_endpoint(endpoint, websocket.client)
        await websocket.close(code=1008, reason="unknown endpoint")
        return
    await gateway.handle(websocket, connection_id)


app.websocket("/{path:path}")(websocket_gateway)
gateway_app.websocket("/{path:path}")(websocket_gateway)


if __name__ == "__main__":
    async def serve() -> None:
        # log_config=None：不让 uvicorn 用默认 dictConfig 覆盖上面的日志接管。
        # 轮询请求由 _SkipControlPlanePollingAccessLog 过滤，其余访问日志照常输出。
        control = uvicorn.Server(
            uvicorn.Config(
                app,
                host=config.host,
                port=config.port,
                log_config=None,
                log_level="info",
            )
        )
        gateway_server = uvicorn.Server(
            uvicorn.Config(
                gateway_app,
                host=config.ws_host,
                port=config.ws_port,
                log_config=None,
                log_level="info",
            )
        )
        await asyncio.gather(control.serve(), gateway_server.serve())

    asyncio.run(serve())
