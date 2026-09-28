from __future__ import annotations

import asyncio
import contextlib
import logging
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Request, WebSocket
import uvicorn

from adapter_host.config import HostConfig
from adapter_host.event_ingress import EventIngress
from adapter_host.gateway import WebSocketGateway
from adapter_host.models import DesiredConnection
from adapter_host.redis_bus import RedisWorkflowBus
from adapter_host.supervisor import AdapterSupervisor

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
config = HostConfig.from_env()
redis_bus = RedisWorkflowBus(config.redis_url, config.task_stream, config.index_prefix, config.task_ttl_seconds)
supervisor = AdapterSupervisor(config, EventIngress(redis_bus))
gateway = WebSocketGateway(supervisor)


@asynccontextmanager
async def lifespan(_: FastAPI):
    try:
        yield
    finally:
        await supervisor.stop()
        await redis_bus.close()


app = FastAPI(title="Loom Adapter Supervisor", docs_url=None, redoc_url=None, lifespan=lifespan)
gateway_app = FastAPI(title="Loom Adapter Gateway", docs_url=None, redoc_url=None)


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


@app.post("/internal/actions/{connection_id}")
async def action(connection_id: int, request: Request, x_adapter_token: str | None = Header(default=None)) -> dict[str, Any]:
    require_token(x_adapter_token)
    body = await request.json()
    try:
        return {"status": "SUCCEEDED", "result": await supervisor.invoke(connection_id, str(body.get("action")), dict(body.get("params") or {}))}
    except asyncio.TimeoutError as exc:
        return {"status": "UNKNOWN", "errorCode": "ACTION_TIMEOUT", "errorMessage": str(exc)}
    except Exception as exc:
        return {"status": "FAILED", "errorCode": "ACTION_FAILED", "errorMessage": str(exc)}


async def websocket_gateway(websocket: WebSocket, path: str) -> None:
    endpoint = "/" + path
    connection_id = supervisor.endpoint_connection(endpoint)
    if connection_id is None:
        await websocket.close(code=1008, reason="unknown endpoint")
        return
    await gateway.handle(websocket, connection_id)


app.websocket("/{path:path}")(websocket_gateway)
gateway_app.websocket("/{path:path}")(websocket_gateway)


if __name__ == "__main__":
    async def serve() -> None:
        control = uvicorn.Server(uvicorn.Config(app, host=config.host, port=config.port, log_level="info"))
        gateway_server = uvicorn.Server(uvicorn.Config(gateway_app, host=config.ws_host, port=config.ws_port, log_level="info"))
        await asyncio.gather(control.serve(), gateway_server.serve())

    asyncio.run(serve())
