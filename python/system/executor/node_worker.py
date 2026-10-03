"""插件节点宿主进程。

**一个插件版本一个常驻进程，用插件自己的私有 venv 解释器启动。** 这是依赖隔离的全部：
插件把包装进 `<pluginDir>/.venv`，就用那个解释器跑它的代码，宿主环境永远不用为插件装包，
插件之间也不会互相覆盖版本。顺带拿到崩溃隔离——插件把进程搞挂，执行器还在。

执行器通过 stdin/stdout 的行式 JSON 和它说话（值本身走 `system.executor.wire` 的带类型编码）：

    host -> worker   {"type": "invoke", "id", "nodeKey", "args", "kwargs", "ctx"}
    worker -> host   {"type": "result", "id", "ok", "value" | "error"}
    worker -> host   {"type": "action", "id", "invokeId", "connectionId", "nodeKey", "params"}
    host -> worker   {"type": "action_result", "id", "ok", "value" | "error"}

启动时先把节点目录（键 + 参数签名）报给宿主。**签名在插件侧内省**，宿主不 import 插件代码，
也就不会把插件依赖拖进自己的环境。

插件的 `print` 会被改道到 stderr（见 `_protocol_stream`），不然一行输出就能把协议搅乱。
"""

from __future__ import annotations

import argparse
import asyncio
import inspect
import os
import sys
import threading
from functools import partial
from pathlib import Path
from typing import Any, Callable

from system.executor.context import ExecutionContext
from system.executor.errors import ActionError
from system.executor.wire import dumps, loads
from system.scanner.contract import load_sub_scanner
from system.scanner.signature import describe_parameters, takes_ctx

_PROTOCOL_STREAM: Any = None


def _protocol_stream() -> Any:
    """协议输出流：复制一份 stdout 给自己，然后把 `sys.stdout` 换成 stderr。

    插件代码里一句 `print` 就会往 stdout 写一行，宿主那边直接解析失败。复制完就把
    `sys.stdout` 指到 stderr，插件爱打印打印，协议这条管道保持干净。
    """
    global _PROTOCOL_STREAM
    if _PROTOCOL_STREAM is None:
        _PROTOCOL_STREAM = os.fdopen(
            os.dup(sys.stdout.fileno()), "w", encoding="utf-8", newline="\n"
        )
        sys.stdout = sys.stderr
    return _PROTOCOL_STREAM


def _emit(message: dict[str, Any]) -> None:
    stream = _protocol_stream()
    stream.write(dumps(message))
    stream.write("\n")
    stream.flush()


class NodeWorker:
    """加载一个插件版本的节点，并响应宿主的调用请求。"""

    def __init__(self, plugin_dir: Path, scanner_name: str, repo_folder: Path) -> None:
        self.plugin_dir = plugin_dir
        self.scanner_name = scanner_name
        self.repo_folder = repo_folder
        self.nodes: dict[str, Callable[..., Any]] = {}
        self.descriptors: dict[str, dict[str, Any]] = {}
        self.ctx_flags: dict[str, bool] = {}
        self._pending_actions: dict[int, asyncio.Future[Any]] = {}
        self._action_seq = 0
        self._queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()

    def load(self) -> None:
        """问插件库的子扫描器要节点，并把参数签名一并算好。"""
        scanner = load_sub_scanner(
            self.scanner_name, self.repo_folder, required=("load_nodes",)
        )
        for key, func in scanner.load_nodes(self.plugin_dir).items():
            parameters, _, _ = describe_parameters(func)
            pass_ctx = takes_ctx(func)
            self.nodes[key] = func
            self.ctx_flags[key] = pass_ctx
            self.descriptors[key] = {
                "key": key,
                "parameters": parameters,
                "passCtx": pass_ctx,
            }

    async def run(self) -> None:
        loop = asyncio.get_running_loop()
        threading.Thread(
            target=self._pump_stdin, args=(loop,), daemon=True, name="node-worker-stdin"
        ).start()
        _emit({"type": "ready", "nodes": self.descriptors})
        while True:
            message = await self._queue.get()
            if message is None:
                return
            await self._dispatch(message)

    def _pump_stdin(self, loop: asyncio.AbstractEventLoop) -> None:
        """阻塞读 stdin（放在线程里），解出消息丢回事件循环。"""
        for line in sys.stdin:
            text = line.strip()
            if not text:
                continue
            try:
                message = loads(text)
            except Exception:  # noqa: BLE001 - 一行坏了不该带走整个进程
                print(f"[node-worker] 无法解析宿主消息，已忽略: {text[:200]}", file=sys.stderr)
                continue
            loop.call_soon_threadsafe(self._queue.put_nowait, message)
        loop.call_soon_threadsafe(self._queue.put_nowait, None)

    async def _dispatch(self, message: dict[str, Any]) -> None:
        kind = str(message.get("type") or "")
        if kind == "invoke":
            # 不 await：一次工作流里多个节点、多条工作流都可能同时在跑。
            asyncio.create_task(self._invoke(message))
        elif kind == "action_result":
            self._resolve_action(message)
        elif kind == "shutdown":
            self._queue.put_nowait(None)

    async def _invoke(self, message: dict[str, Any]) -> None:
        request_id = message.get("id")
        key = str(message.get("nodeKey") or "")
        func = self.nodes.get(key)
        if func is None:
            _emit(
                {
                    "type": "result",
                    "id": request_id,
                    "ok": False,
                    "error": {"code": "NODE_FAILED", "message": f"插件里没有节点 {key}"},
                }
            )
            return
        args = list(message.get("args") or [])
        kwargs = dict(message.get("kwargs") or {})
        try:
            ctx = self._context(message.get("ctx") or {}, key, request_id)
            call_args = [ctx, *args] if self.ctx_flags.get(key) else args
            value = await _call(func, call_args, kwargs)
        except Exception as exc:  # noqa: BLE001 - 插件异常一律带回宿主分类
            _emit(
                {
                    "type": "result",
                    "id": request_id,
                    "ok": False,
                    "error": {"code": _code_of(exc), "message": str(exc)},
                }
            )
            return
        _emit({"type": "result", "id": request_id, "ok": True, "value": value})

    def _context(self, payload: dict[str, Any], node_key: str, invoke_id: Any) -> ExecutionContext:
        """复用宿主那份 `ExecutionContext`，只是把 `call_action` 换成回传宿主的实现。"""
        return ExecutionContext(
            execution_id=str(payload.get("executionId") or ""),
            trace_id=str(payload.get("traceId") or ""),
            workflow_id=int(payload.get("workflowId") or 0),
            workflow_version_id=int(payload.get("workflowVersionId") or 0),
            node_id=str(payload.get("nodeId") or ""),
            node_key=str(payload.get("nodeKey") or node_key),
            deadline_ms=int(payload.get("deadlineMs") or 0),
            trigger=dict(payload.get("trigger") or {}),
            action_caller=partial(self._call_action, invoke_id),
        )

    async def _call_action(
        self, invoke_id: Any, connection_id: int, node_key: str, params: dict[str, Any]
    ) -> Any:
        """节点里的 `ctx.call_action`：请求宿主去调适配器，等它把结果送回来。"""
        self._action_seq += 1
        action_id = self._action_seq
        future: asyncio.Future[Any] = asyncio.get_running_loop().create_future()
        self._pending_actions[action_id] = future
        _emit(
            {
                "type": "action",
                "id": action_id,
                "invokeId": invoke_id,
                "connectionId": int(connection_id),
                "nodeKey": str(node_key),
                "params": dict(params or {}),
            }
        )
        try:
            return await future
        finally:
            self._pending_actions.pop(action_id, None)

    def _resolve_action(self, message: dict[str, Any]) -> None:
        future = self._pending_actions.get(int(message.get("id") or 0))
        if future is None or future.done():
            return
        if message.get("ok"):
            future.set_result(message.get("value"))
            return
        error = message.get("error") or {}
        future.set_exception(
            ActionError(
                str(error.get("message") or "适配器动作失败"),
                code=str(error.get("code") or "ACTION_FAILED"),
            )
        )


async def _call(func: Callable[..., Any], args: list[Any], kwargs: dict[str, Any]) -> Any:
    """同步函数放线程池，别把插件自己的事件循环卡住。"""
    if inspect.iscoroutinefunction(func):
        return await func(*args, **kwargs)
    return await asyncio.to_thread(func, *args, **kwargs)


def _code_of(exc: BaseException) -> str:
    """异常带回宿主时保留分类错误码，其余统一 NODE_FAILED。"""
    return str(getattr(exc, "code", "") or "NODE_FAILED")


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="LoomBot 插件节点宿主进程")
    parser.add_argument("--plugin-dir", required=True)
    parser.add_argument("--scanner", required=True)
    parser.add_argument("--repo", required=True)
    return parser.parse_args()


def main() -> None:
    # 先接管 stdout，再加载插件——插件 import 期间就可能 print。
    _protocol_stream()
    args = _parse_args()
    worker = NodeWorker(
        Path(args.plugin_dir).resolve(), args.scanner, Path(args.repo).resolve()
    )
    try:
        worker.load()
    except Exception as exc:  # noqa: BLE001 - 加载失败要让宿主拿到原因，而不是只看到进程退出
        _emit({"type": "error", "message": str(exc)})
        raise SystemExit(1) from exc
    asyncio.run(worker.run())


if __name__ == "__main__":
    main()
