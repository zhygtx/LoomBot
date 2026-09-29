"""工作流运行时进程：消费任务、执行 DAG、回写执行日志。"""

from __future__ import annotations

import asyncio
import json
import logging
import sys
import uuid
from typing import Any

import redis.asyncio as redis
import uvicorn
from fastapi import FastAPI, Header, HTTPException, Request

from system.executor.config import WorkerConfig
from system.executor.context import ActionClient
from system.executor.definitions import DefinitionClient
from system.executor.engine import STATUS_FAILED, WorkflowEngine, now_ms
from system.executor.errors import WorkflowError
from system.executor.plugins import PluginRegistry

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
    stream=sys.stderr,
)
log = logging.getLogger("workflow-worker")

MAX_DETAIL_CHARS = 16 * 1024
MAX_TASKS_IN_FLIGHT = 128


class WorkerHost:
    def __init__(self, config: WorkerConfig) -> None:
        self.config = config
        self.redis = redis.from_url(config.redis_url, decode_responses=True)
        self.definitions = DefinitionClient(config.java_base_url, config.workflow_token)
        self.engine = WorkflowEngine(
            PluginRegistry(),
            ActionClient(config.adapter_base_url, config.adapter_token),
        )
        self.semaphore = asyncio.Semaphore(config.max_concurrency)
        self.test_semaphore = asyncio.Semaphore(config.max_concurrency)
        self.test_app = self._build_test_app()
        self.tasks: set[asyncio.Task[Any]] = set()
        self.expired = 0

    async def run(self) -> None:
        server = uvicorn.Server(
            uvicorn.Config(
                self.test_app,
                host=self.config.test_host,
                port=self.config.test_port,
                log_level="info",
                log_config=None,
            )
        )
        await asyncio.gather(self._consume_loop(), server.serve())

    async def _consume_loop(self) -> None:
        while True:
            try:
                await self._ensure_group()
                break
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - Redis 恢复后继续正式消费
                log.exception("初始化工作流消费组失败，2 秒后重试")
                await asyncio.sleep(2)
        log.info(
            "工作流运行时已启动: stream=%s group=%s consumer=%s concurrency=%s",
            self.config.task_stream,
            self.config.consumer_group,
            self.config.consumer_name,
            self.config.max_concurrency,
        )
        while True:
            try:
                entries = await self.redis.xreadgroup(
                    self.config.consumer_group,
                    self.config.consumer_name,
                    {self.config.task_stream: ">"},
                    count=8,
                    block=2000,
                )
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - Redis 抖动时重试，不让进程退出
                log.exception("读取任务失败，稍后重试")
                await asyncio.sleep(2)
                continue
            for _stream, messages in entries or []:
                for message_id, fields in messages:
                    if len(self.tasks) >= MAX_TASKS_IN_FLIGHT:
                        # 在途任务过多时先等一会，避免无限堆积
                        await asyncio.sleep(0.05)
                    task = asyncio.create_task(self._handle(message_id, fields))
                self.tasks.add(task)
                task.add_done_callback(self.tasks.discard)

    def _build_test_app(self) -> FastAPI:
        app = FastAPI(title="Loom Workflow Test Runtime", docs_url=None, redoc_url=None)

        @app.get("/internal/health")
        async def health() -> dict[str, str]:
            return {"status": "UP"}

        @app.post("/internal/workflow/test")
        async def test_workflow(
            request: Request,
            x_workflow_token: str | None = Header(default=None),
        ) -> dict[str, Any]:
            if x_workflow_token != self.config.workflow_token:
                raise HTTPException(status_code=401, detail="invalid workflow test token")
            try:
                payload = await request.json()
                version_id = int(payload.get("workflowVersionId") or 0)
            except (TypeError, ValueError):
                raise HTTPException(status_code=400, detail="workflowVersionId 必须是整数")
            if version_id <= 0:
                raise HTTPException(status_code=400, detail="workflowVersionId 必须是正整数")

            async with self.test_semaphore:
                try:
                    fetched = await self.definitions.fetch(version_id)
                except WorkflowError as exc:
                    raise HTTPException(status_code=502, detail=exc.message) from exc
                definition = fetched["definition"]
                if str(definition.get("eventNodeId") or "").strip():
                    raise HTTPException(status_code=400, detail="包含事件节点的工作流不能直接测试")
                start_node_ids = _root_node_ids(definition)
                if not start_node_ids:
                    raise HTTPException(status_code=400, detail="工作流没有可执行的起始节点")
                execution_id = f"test-{uuid.uuid4()}"
                job = {
                    "messageId": execution_id,
                    "executionId": execution_id,
                    "eventId": "",
                    "traceId": uuid.uuid4().hex,
                    "connectionId": None,
                    "connectionType": "",
                    "pluginVersionId": None,
                    "nodeKey": "",
                    "workflowVersionId": version_id,
                    "event": {},
                    "deadline": now_ms()
                    + int(self.config.execution_timeout_seconds * 1000),
                }
                outcome = await self.engine.execute(
                    job,
                    definition,
                    fetched["plugins"],
                    self.config.execution_timeout_seconds,
                    start_node_ids=start_node_ids,
                )
                detail = outcome.detail()
                traces = detail.get("nodes") or []
                return {
                    "executionId": execution_id,
                    "status": outcome.status,
                    "errorCode": outcome.error_code,
                    "errorMessage": outcome.error_message,
                    "durationMs": max(0, outcome.end_ms - outcome.start_ms),
                    "nodes": traces,
                    "terminalNodeIds": _terminal_node_ids(definition, traces),
                }

        return app

    async def _ensure_group(self) -> None:
        try:
            await self.redis.xgroup_create(
                self.config.task_stream, self.config.consumer_group, id="0", mkstream=True
            )
        except Exception as exc:  # noqa: BLE001 - 已存在时忽略
            if "BUSYGROUP" not in str(exc):
                raise

    async def _handle(self, message_id: str, fields: dict[str, Any]) -> None:
        async with self.semaphore:
            job = _parse_job(fields)
            if job is None:
                log.warning("任务字段不完整，丢弃: %s", message_id)
                await self._ack(message_id)
                return
            if job["deadline"] and now_ms() > job["deadline"]:
                self.expired += 1
                log.warning("任务已过期，丢弃: execution=%s", job["executionId"])
                await self._ack(message_id)
                return
            try:
                fetched = await self.definitions.fetch(job["workflowVersionId"])
            except WorkflowError as exc:
                await self._publish_log(
                    job,
                    workflow_id=0,
                    status=STATUS_FAILED,
                    error_code=exc.code,
                    error_message=exc.message,
                    detail={},
                    start_ms=now_ms(),
                    end_ms=now_ms(),
                )
                await self._ack(message_id)
                return
            outcome = await self.engine.execute(
                job,
                fetched["definition"],
                fetched["plugins"],
                self.config.execution_timeout_seconds,
            )
            await self._publish_log(
                job,
                workflow_id=int(fetched.get("workflowId") or 0),
                status=outcome.status,
                error_code=outcome.error_code,
                error_message=outcome.error_message,
                detail=outcome.detail(),
                start_ms=outcome.start_ms,
                end_ms=outcome.end_ms,
            )
            await self._ack(message_id)

    async def _ack(self, message_id: str) -> None:
        try:
            await self.redis.xack(
                self.config.task_stream, self.config.consumer_group, message_id
            )
        except Exception:  # noqa: BLE001 - 确认失败交给下次重读
            log.exception("确认任务失败: %s", message_id)

    async def _publish_log(
        self,
        job: dict[str, Any],
        *,
        workflow_id: int,
        status: str,
        error_code: str | None,
        error_message: str | None,
        detail: dict[str, Any],
        start_ms: int,
        end_ms: int,
    ) -> None:
        try:
            detail_text = json.dumps(detail, ensure_ascii=False, default=str)
        except (TypeError, ValueError):
            detail_text = "{}"
        truncated = False
        if len(detail_text) > MAX_DETAIL_CHARS:
            detail_text = json.dumps(
                {
                    "errorCode": error_code,
                    "errorMessage": error_message,
                    "nodes": [
                        {
                            "nodeId": item.get("nodeId"),
                            "nodeKey": item.get("nodeKey"),
                            "status": item.get("status"),
                            "error": item.get("error"),
                        }
                        for item in (detail.get("nodes") or [])
                    ],
                    "truncated": True,
                },
                ensure_ascii=False,
                default=str,
            )
            truncated = True
        fields = {
            "executionId": job["executionId"],
            "traceId": job["traceId"],
            "workflowId": str(workflow_id),
            "definitionVersion": str(job["workflowVersionId"]),
            "connectionId": "" if job["connectionId"] is None else str(job["connectionId"]),
            "adapterPluginVersionId": ""
            if job["pluginVersionId"] is None
            else str(job["pluginVersionId"]),
            "connectionType": job["connectionType"] or "",
            "nodeKey": job["nodeKey"] or "",
            "status": status,
            "errorCode": error_code or "",
            "errorMessage": (error_message or "")[:2000],
            "startTime": str(start_ms),
            "endTime": str(end_ms),
            "durationMs": str(max(0, end_ms - start_ms)),
            "detailJson": detail_text,
            "detailTruncated": "1" if truncated else "0",
        }
        try:
            await self.redis.xadd(self.config.log_stream, fields, maxlen=100000, approximate=True)
        except Exception:  # noqa: BLE001 - 日志写入失败只记本地日志
            log.exception("写执行日志失败: execution=%s", job["executionId"])


def _parse_job(fields: dict[str, Any]) -> dict[str, Any] | None:
    try:
        event = json.loads(fields.get("event") or "{}")
    except (TypeError, ValueError):
        event = {}
    execution_id = str(fields.get("executionId") or "")
    version_id = str(fields.get("workflowVersionId") or "")
    if not execution_id or not version_id:
        return None

    def optional_int(value: Any) -> int | None:
        try:
            text = str(value or "").strip()
            return int(text) if text else None
        except (TypeError, ValueError):
            return None

    return {
        "messageId": str(fields.get("messageId") or ""),
        "executionId": execution_id,
        "eventId": str(fields.get("eventId") or ""),
        "traceId": str(fields.get("traceId") or ""),
        "connectionId": optional_int(fields.get("connectionId")),
        "connectionType": str(fields.get("connectionType") or ""),
        "pluginVersionId": optional_int(fields.get("pluginVersionId")),
        "nodeKey": str(fields.get("nodeKey") or ""),
        "workflowVersionId": int(version_id),
        "event": event if isinstance(event, dict) else {},
        "deadline": optional_int(fields.get("deadline")) or 0,
    }


def _root_node_ids(definition: dict[str, Any]) -> list[str]:
    node_ids = [str(node.get("id") or "") for node in definition.get("nodes") or []]
    node_ids = [node_id for node_id in node_ids if node_id]
    targets = {
        str(edge.get("to") or "")
        for edge in definition.get("edges") or []
        if edge.get("to")
    }
    return [node_id for node_id in node_ids if node_id not in targets]


def _terminal_node_ids(
    definition: dict[str, Any], traces: list[dict[str, Any]]
) -> list[str]:
    executed = {
        str(trace.get("nodeId") or "")
        for trace in traces
        if trace.get("nodeId") is not None
    }
    sources = {
        str(edge.get("from") or "")
        for edge in definition.get("edges") or []
        if edge.get("from")
    }
    return [
        str(node.get("id") or "")
        for node in definition.get("nodes") or []
        if str(node.get("id") or "") in executed
        and str(node.get("id") or "") not in sources
    ]


def main() -> None:
    config = WorkerConfig.from_env()
    try:
        asyncio.run(WorkerHost(config).run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
