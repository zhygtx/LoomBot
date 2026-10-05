"""DAG 执行引擎。

按边激活与跳过推进：普通节点激活全部非 failure 出边，分支节点只激活 success 或 failure 出边；
目标节点所有入边处理完后如果没有任何活动入边，就标记跳过并级联跳过它的下游。
"""

from __future__ import annotations

import asyncio
import dataclasses
import hashlib
import json
import logging
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from system.executor import expressions
from system.executor.context import ActionClient, ExecutionContext
from system.executor.convert import convert
from system.executor.errors import (
    ActionError,
    DefinitionError,
    NodeError,
    ParamError,
    WorkflowError,
)
from system.executor.plugins import PluginRegistry

log = logging.getLogger("workflow-engine")

STATUS_SUCCESS = "SUCCESS"
STATUS_TIMEOUT = "TIMEOUT"
STATUS_FAILED = "FAILED"

# 内联进 detail_json 的上限；超过就走引用，内容原样交给 Java 落库/落盘，不截断。
MAX_INLINE_CHARS = 4096
# 引用标记里留的预览长度：够看清是什么，又不至于把 detail_json 撑大。
PREVIEW_CHARS = 200
# 单条大内容的硬上限，超过只留元信息（防止一次误传把整条链路打爆）。
MAX_PAYLOAD_CHARS = 4 * 1024 * 1024


def now_ms() -> int:
    return int(time.time() * 1000)


@dataclass
class PayloadStore:
    """一次执行里的大内容暂存。

    - 大文本：完整内容放进 `payloads`，随执行日志一起交给 Java 落表；
    - 二进制/文件：直接写到 artifact 目录，日志里只留引用，Java 按引用提供下载。

    两条路都不截断内容，日志里只放一个几百字符的引用标记；前端拿到引用先不请求，
    用户点开时才去取。
    """

    execution_id: str
    artifact_dir: Path | None = None
    payloads: list[dict[str, Any]] = field(default_factory=list)
    _next: int = 0

    def _ref(self, prefix: str) -> str:
        self._next += 1
        return f"{prefix}{self._next}"

    def add_text(self, text: str, content_type: str, size: int, digest: str, preview: str) -> str:
        ref = self._ref("p")
        self.payloads.append(
            {
                "ref": ref,
                "kind": "text",
                "contentType": content_type,
                "size": size,
                "sha256": digest,
                "content": text,
            }
        )
        return ref

    def add_file(
        self, raw: bytes, file_name: str, content_type: str, suffix: str = ""
    ) -> str:
        ref = self._ref("f")
        if file_name:
            stored = f"{ref}-{file_name}"
        else:
            stored = f"{ref}{suffix or '.bin'}"
        if self.artifact_dir is not None:
            target_dir = self.artifact_dir / self.execution_id
            target_dir.mkdir(parents=True, exist_ok=True)
            (target_dir / stored).write_bytes(raw)
        return stored


def encode_value(value: Any, store: PayloadStore | None = None) -> Any:
    """把节点输入/输出编码进日志。超限内容转成引用，不截断。"""
    if isinstance(value, (bytes, bytearray, memoryview)):
        return _encode_binary(bytes(value), store)
    try:
        text = json.dumps(value, ensure_ascii=False, default=_jsonable)
    except (TypeError, ValueError):
        text = str(value)
    if len(text) <= MAX_INLINE_CHARS:
        try:
            return json.loads(text)
        except (TypeError, ValueError):
            return text
    digest = hashlib.sha256(text.encode("utf-8", "surrogatepass")).hexdigest()
    marker: dict[str, Any] = {
        "truncated": True,
        "kind": "text",
        "type": type(value).__name__,
        "size": len(text),
        "sha256": digest,
        "preview": text[:PREVIEW_CHARS],
    }
    if len(text) > MAX_PAYLOAD_CHARS:
        # 太大就不搬了，但要明确标出来，别让人以为日志里就是全部
        marker["contentOmitted"] = True
        return marker
    if store is None:
        return marker
    marker["ref"] = store.add_text(
        text, "application/json", len(text), digest, marker["preview"]
    )
    return marker


def _encode_binary(raw: bytes, store: PayloadStore | None) -> dict[str, Any]:
    digest = hashlib.sha256(raw).hexdigest()
    suffix, content_type = _sniff_binary(raw)
    marker: dict[str, Any] = {
        "truncated": True,
        "kind": "file",
        "type": "bytes",
        "size": len(raw),
        "sha256": digest,
        "contentType": content_type,
    }
    if len(raw) > MAX_PAYLOAD_CHARS:
        marker["contentOmitted"] = True
        return marker
    if store is None:
        return marker
    marker["fileName"] = store.add_file(raw, "", content_type, suffix)
    return marker


def _sniff_binary(raw: bytes) -> tuple[str, str]:
    """按文件头猜出二进制类型，返回 (扩展名, MIME)。

    节点只声明返回 bytes，执行器不知道具体是什么格式；不猜的话所有产物都会
    落成 `.bin`/octet-stream，前端下载截图时只能看到 `f1.bin`。只认可靠的
    文件头，认不出仍按通用二进制处理。
    """
    head = bytes(raw[:16])
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return ".png", "image/png"
    if head.startswith(b"\xff\xd8\xff"):
        return ".jpg", "image/jpeg"
    if head.startswith((b"GIF87a", b"GIF89a")):
        return ".gif", "image/gif"
    if head.startswith(b"RIFF") and head[8:12] == b"WEBP":
        return ".webp", "image/webp"
    if head.startswith(b"RIFF") and head[8:12] == b"WAVE":
        return ".wav", "audio/wav"
    if head.startswith(b"BM"):
        return ".bmp", "image/bmp"
    if head.startswith(b"%PDF-"):
        return ".pdf", "application/pdf"
    if head.startswith((b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")):
        return ".zip", "application/zip"
    if head.startswith(b"\x1f\x8b"):
        return ".gz", "application/gzip"
    if len(head) >= 8 and head[4:8] == b"ftyp":
        return ".mp4", "video/mp4"
    if head.startswith(b"ID3") or head[:2] in (b"\xff\xfb", b"\xff\xf3", b"\xff\xf2"):
        return ".mp3", "audio/mpeg"
    return ".bin", "application/octet-stream"


def _jsonable(value: Any) -> Any:
    """json.dumps 的兜底：数据类按字段展开，集合转数组，其余退回字符串。"""
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return dataclasses.asdict(value)
    if isinstance(value, (set, frozenset, tuple)):
        return list(value)
    return str(value)


def runtime_fields(
    value: Any,
    prefix: str = "",
    depth: int = 0,
    seen: set[int] | None = None,
) -> list[dict[str, Any]]:
    """递归记录运行时实际字段，供画布排查数据来源。"""
    if not isinstance(value, dict):
        return []
    visited = seen or set()
    identity = id(value)
    if identity in visited:
        return []
    visited.add(identity)
    fields: list[dict[str, Any]] = []
    for key, item in value.items():
        if len(fields) >= 50:
            break
        name = str(key)
        path = f"{prefix}.{name}" if prefix else name
        fields.append(
            {
                "name": name,
                "type": type(item).__name__,
                "path": path,
                "depth": depth,
            }
        )
        if isinstance(item, dict):
            remaining = 50 - len(fields)
            fields.extend(runtime_fields(item, path, depth + 1, visited)[:remaining])
    visited.discard(identity)
    return fields


@dataclass
class Outcome:
    """一次执行的结果。"""

    status: str = STATUS_SUCCESS
    error_code: str | None = None
    error_message: str | None = None
    start_ms: int = field(default_factory=now_ms)
    end_ms: int = 0
    traces: list[dict[str, Any]] = field(default_factory=list)
    result_known: bool = True
    trigger: dict[str, Any] = field(default_factory=dict)
    store: PayloadStore | None = None

    def detail(self) -> dict[str, Any]:
        return {
            "trigger": self.trigger,
            "nodes": self.traces,
            "resultKnown": self.result_known,
        }


class WorkflowEngine:
    """按定义执行一次工作流。"""

    def __init__(
        self,
        plugins: PluginRegistry,
        actions: ActionClient,
        artifact_dir: Path | None = None,
    ) -> None:
        self.plugins = plugins
        self.actions = actions
        # 大内容落盘的位置；None 表示只留引用标记（开发期没配目录时的兜底）
        self.artifact_dir = artifact_dir

    def encode(self, outcome: Outcome, value: Any) -> Any:
        """把节点的输入/输出编码进日志，超限内容转成引用。"""
        if outcome.store is None:
            return encode_value(value)
        return encode_value(value, outcome.store)

    async def execute(
        self,
        job: dict[str, Any],
        definition: dict[str, Any],
        plugin_versions: dict[int, dict[str, Any]],
        timeout_seconds: float,
        start_node_ids: list[str] | None = None,
    ) -> Outcome:
        outcome = Outcome()
        outcome.store = PayloadStore(
            execution_id=str(job.get("executionId") or ""),
            artifact_dir=self.artifact_dir,
        )
        if start_node_ids is None:
            event_node_id: str | None = str(definition.get("eventNodeId") or "")
            start_node_ids = [event_node_id]
        else:
            event_node_id = None
            start_node_ids = [str(node_id) for node_id in start_node_ids]
        connection_id = job.get("connectionId")
        outcome.trigger = {
            "eventNodeKey": job.get("nodeKey"),
            "eventNodeName": _node_name(definition, event_node_id),
            # 雪花 ID 进入 JSON 日志前必须转成字符串：前端用 Number 解析，
            # 超过 2^53 会直接损失精度，并触发“不安全的数字 ID”。
            "connectionId": None if connection_id in (None, "") else str(connection_id),
            # 先用任务里的载荷兜底；事件节点真正跑起来之后会用它的输出覆盖（见 _run_node）
            "eventSummary": _summarize(job.get("event") or {}),
        }
        try:
            async with asyncio.timeout(timeout_seconds):
                await self._run(
                    job,
                    definition,
                    plugin_versions,
                    outcome,
                    event_node_id,
                    start_node_ids,
                )
        except TimeoutError:
            outcome.status = STATUS_TIMEOUT
            outcome.error_code = "TIMEOUT"
            outcome.error_message = f"工作流执行超时（超过 {timeout_seconds} 秒）"
        except WorkflowError as exc:
            outcome.status = STATUS_FAILED
            outcome.error_code = exc.code
            outcome.error_message = exc.message
        except Exception as exc:  # noqa: BLE001 - 未分类异常统一记失败
            log.exception("工作流执行失败: execution=%s", job.get("executionId"))
            outcome.status = STATUS_FAILED
            outcome.error_code = "NODE_FAILED"
            outcome.error_message = str(exc)
        outcome.end_ms = now_ms()
        return outcome

    async def _run(
        self,
        job: dict[str, Any],
        definition: dict[str, Any],
        plugin_versions: dict[int, dict[str, Any]],
        outcome: Outcome,
        event_node_id: str | None,
        start_node_ids: list[str],
    ) -> None:
        nodes = definition.get("nodes") or []
        edges = definition.get("edges") or []
        node_map = {str(node.get("id")): node for node in nodes}
        if not start_node_ids or any(node_id not in node_map for node_id in start_node_ids):
            raise DefinitionError("定义缺少可执行的起始节点")

        outgoing: dict[str, list[dict[str, Any]]] = {key: [] for key in node_map}
        pending: dict[str, int] = {key: 0 for key in node_map}
        for edge in edges:
            source, target = str(edge.get("from")), str(edge.get("to"))
            if source not in node_map or target not in node_map:
                raise DefinitionError(f"连线引用了不存在的节点：{source} -> {target}")
            pending[target] += 1
            outgoing[source].append(edge)

        context: dict[str, Any] = {"input": job.get("event") or {}}
        processed: set[str] = set()
        skipped: set[str] = set()
        completed: set[str] = set()
        active_incoming: dict[str, int] = {key: 0 for key in node_map}
        queue: deque[str] = deque(start_node_ids)
        queued = set(start_node_ids)

        while queue:
            node_id = queue.popleft()
            if node_id in skipped or node_id in completed:
                continue
            node = node_map[node_id]
            result = await self._run_node(
                node, node_id, event_node_id, job, context, plugin_versions, outcome
            )
            context[node_id] = result
            completed.add(node_id)

            activated = self._select_edges(node, outgoing[node_id], result)
            activated_keys = {_edge_key(edge) for edge in activated}
            for edge in activated:
                self._activate(
                    edge, pending, active_incoming, processed, skipped, completed, queue, queued
                )
            for edge in outgoing[node_id]:
                if _edge_key(edge) not in activated_keys:
                    self._skip(
                        edge,
                        outgoing,
                        pending,
                        active_incoming,
                        processed,
                        skipped,
                        completed,
                        queue,
                        queued,
                    )

    async def _run_node(
        self,
        node: dict[str, Any],
        node_id: str,
        event_node_id: str | None,
        job: dict[str, Any],
        context: dict[str, Any],
        plugin_versions: dict[int, dict[str, Any]],
        outcome: Outcome,
    ) -> Any:
        node_key = str(node.get("nodeKey") or "")
        trace: dict[str, Any] = {
            "nodeId": node_id,
            "nodeKey": node_key,
            "name": str(node.get("name") or node_key),
            "status": "RUNNING",
            "startTime": now_ms(),
            "endTime": None,
            "input": None,
            "output": None,
            "runtimeFields": [],
            "error": None,
            "errorCode": None,
        }
        outcome.traces.append(trace)
        try:
            if event_node_id is not None and node_id == event_node_id:
                result = context.get("input") or {}
                trace["input"] = self.encode(outcome, node.get("config") or {})
                trace["output"] = self.encode(outcome, result)
                trace["runtimeFields"] = runtime_fields(result)
                trace["status"] = "SUCCESS"
                trace["endTime"] = now_ms()
                # 事件摘要 = 事件节点的输出内容，事件节点跑完才拿得到
                outcome.trigger["eventSummary"] = _summarize(result)
                return result

            connection_id = node.get("connectionId")
            if connection_id is not None and node.get("connectionType"):
                params = self._resolve_action_params(node, context)
                trace["input"] = self.encode(outcome, params)
                result = await self.actions.call(int(connection_id), node_key, params)
                trace["output"] = self.encode(outcome, result)
                trace["runtimeFields"] = runtime_fields(result)
                trace["status"] = "SUCCESS"
                trace["endTime"] = now_ms()
                return result

            plugin_version_id = int(node.get("pluginVersionId") or 0)
            info = plugin_versions.get(plugin_version_id)
            if not info:
                raise DefinitionError(f"定义引用的插件版本未登记: {plugin_version_id}")
            runtime = await self.plugins.get(
                plugin_version_id,
                str(info.get("installPath") or ""),
                str(info.get("pluginKey") or ""),
                str(info.get("pythonPath") or ""),
                str(info.get("artifactSha256") or ""),
            )
            spec = runtime.nodes.get(node_key)
            if spec is None:
                raise DefinitionError(f"插件版本 {plugin_version_id} 没有节点 {node_key}")
            args, kwargs, input_log = self._bind_parameters(spec.parameters, node, context)
            trace["input"] = self.encode(outcome, input_log)
            ctx = ExecutionContext(
                execution_id=str(job.get("executionId") or ""),
                trace_id=str(job.get("traceId") or ""),
                workflow_id=int(job.get("workflowId") or 0),
                workflow_version_id=int(job.get("workflowVersionId") or 0),
                node_id=node_id,
                node_key=node_key,
                deadline_ms=int(job.get("deadline") or 0),
                trigger=context.get("input") or {},
                action_caller=self.actions.call,
                plugin_key=str(info.get("pluginKey") or ""),
                connection_id=(
                    int(job.get("connectionId"))
                    if job.get("connectionId") not in (None, "")
                    else None
                ),
            )
            result = await runtime.invoke(node_key, ctx, args, kwargs)
            trace["output"] = self.encode(outcome, result)
            trace["runtimeFields"] = runtime_fields(result)
            trace["status"] = "SUCCESS"
            trace["endTime"] = now_ms()
            return result
        except WorkflowError as exc:
            # 适配器动作超时只说明没等到平台响应，动作本身可能已经生效：
            # 节点标 TIMEOUT，并且这条执行的结果按「未知」记，不能当成干净利落的失败。
            action_timeout = exc.code == "ACTION_TIMEOUT"
            trace["status"] = "TIMEOUT" if action_timeout else "FAILED"
            trace["endTime"] = now_ms()
            trace["error"] = exc.message
            trace["errorCode"] = exc.code
            if action_timeout:
                trace["resultKnown"] = False
                outcome.result_known = False
            raise
        except Exception as exc:  # noqa: BLE001 - 插件异常带节点上下文重抛
            trace["status"] = "FAILED"
            trace["endTime"] = now_ms()
            trace["error"] = str(exc)
            trace["errorCode"] = "NODE_FAILED"
            raise NodeError(f"节点 {trace['name']} 执行失败：{exc}") from exc

    def _resolve_action_params(
        self, node: dict[str, Any], context: dict[str, Any]
    ) -> dict[str, Any]:
        params: dict[str, Any] = {}
        for item in node.get("inputs") or []:
            name = str(item.get("paramName") or "").strip()
            if not name:
                continue
            params[name] = self._resolve_input(item, context)
        return params

    def _bind_parameters(
        self, parameters: list[dict[str, Any]], node: dict[str, Any], context: dict[str, Any]
    ) -> tuple[list[Any], dict[str, Any], dict[str, Any]]:
        """按节点自己的参数签名绑定值。

        签名来自插件侧内省（节点进程报回来的），宿主不 import 插件代码，所以这里不能再
        自己去 inspect 那个函数。
        """
        inputs = {
            str(item.get("paramName")): item for item in node.get("inputs") or []
        }
        args: list[Any] = []
        kwargs: dict[str, Any] = {}
        input_log: dict[str, Any] = {}
        for parameter in parameters:
            name = parameter["name"]
            item = inputs.get(name)
            source = str((item or {}).get("source") or "").strip()
            if source:
                value = self._resolve_source(source, context)
            elif item is not None and item.get("defaultValue") is not None:
                value = item["defaultValue"]
            elif parameter["nullable"]:
                value = None
            else:
                raise ParamError(f"参数 {name} 未配置数据来源或默认值")
            try:
                value = convert(value, parameter["type"], parameter["multiType"])
            except ParamError as exc:
                raise ParamError(f"参数 {name}：{exc.message}") from exc
            input_log[name] = value
            if parameter["kind"] == "KEYWORD_ONLY":
                kwargs[name] = value
            else:
                args.append(value)
        return args, kwargs, input_log

    @staticmethod
    def _resolve_input(item: dict[str, Any], context: dict[str, Any]) -> Any:
        source = str(item.get("source") or "").strip()
        if source:
            return WorkflowEngine._resolve_source(source, context)
        return item.get("defaultValue")

    @staticmethod
    def _resolve_source(source: str, context: dict[str, Any]) -> Any:
        parts = source.split(".")
        if not parts:
            return None
        first = parts[0]
        current = context.get("input") if first == "input" else context.get(first)
        for part in parts[1:]:
            if current is None:
                return None
            if isinstance(current, dict):
                current = current.get(part)
            elif isinstance(current, (list, tuple)) and str(part).isdigit():
                index = int(part)
                current = current[index] if index < len(current) else None
            else:
                current = getattr(current, part, None)
        return current

    @staticmethod
    def _select_edges(
        node: dict[str, Any], edges: list[dict[str, Any]], result: Any
    ) -> list[dict[str, Any]]:
        branch = node.get("branch")
        if not isinstance(branch, dict):
            return [edge for edge in edges if _port(edge) != "failure"]
        mode = str(branch.get("mode") or "truthy")
        if mode == "expression":
            ok = expressions.evaluate(str(branch.get("expression") or ""), result)
        else:
            ok = bool(result)
        wanted = "success" if ok else "failure"
        return [edge for edge in edges if _port(edge) == wanted]

    @staticmethod
    def _activate(
        edge: dict[str, Any],
        pending: dict[str, int],
        active_incoming: dict[str, int],
        processed: set[str],
        skipped: set[str],
        completed: set[str],
        queue: deque[str],
        queued: set[str],
    ) -> None:
        key = _edge_key(edge)
        if key in processed:
            return
        processed.add(key)
        target = str(edge.get("to"))
        active_incoming[target] = active_incoming.get(target, 0) + 1
        pending[target] = max(0, pending.get(target, 0) - 1)
        if pending[target] == 0 and target not in skipped and target not in completed:
            if target not in queued:
                queue.append(target)
                queued.add(target)

    @staticmethod
    def _skip(
        edge: dict[str, Any],
        outgoing: dict[str, list[dict[str, Any]]],
        pending: dict[str, int],
        active_incoming: dict[str, int],
        processed: set[str],
        skipped: set[str],
        completed: set[str],
        queue: deque[str],
        queued: set[str],
    ) -> None:
        key = _edge_key(edge)
        if key in processed:
            return
        processed.add(key)
        target = str(edge.get("to"))
        pending[target] = max(0, pending.get(target, 0) - 1)
        if pending[target] != 0 or target in completed:
            return
        if active_incoming.get(target, 0) > 0:
            if target not in skipped and target not in queued:
                queue.append(target)
                queued.add(target)
            return
        if target in skipped:
            return
        skipped.add(target)
        for next_edge in outgoing.get(target) or []:
            WorkflowEngine._skip(
                next_edge,
                outgoing,
                pending,
                active_incoming,
                processed,
                skipped,
                completed,
                queue,
                queued,
            )


def _port(edge: dict[str, Any]) -> str:
    return str(edge.get("port") or "success")


def _edge_key(edge: dict[str, Any]) -> str:
    return f"{edge.get('from')}\u0000{edge.get('to')}\u0000{_port(edge)}"


def _summarize(event: dict[str, Any]) -> str:
    """事件节点输出的短摘要：取第一个像文本的字段，取不到就留空。

    不按平台字段名（group_id / chat_id …）猜内容：各平台不一致，猜出来的东西放进列表只会误导。
    """
    if not isinstance(event, dict):
        return ""
    for key in ("raw_message", "content", "message", "notice_type", "request_type"):
        value = event.get(key)
        if isinstance(value, str) and value:
            return value[:200]
    return ""


def _node_name(definition: dict[str, Any], node_id: str | None) -> str:
    """触发节点的展示名；测试执行没有事件节点，返回空串。"""
    if not node_id:
        return ""
    for node in definition.get("nodes") or []:
        if str(node.get("id") or "") == node_id:
            return str(node.get("name") or node.get("nodeKey") or "")
    return ""
