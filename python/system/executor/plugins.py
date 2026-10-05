"""按插件版本加载并调用工作流节点。

节点代码跑在**插件自己的进程**里（`system.executor.node_worker`），用插件私有 venv 的解释器
启动，所以插件依赖不会漏进宿主环境、插件之间也不会互相覆盖版本。这里负责：

- 定位插件版本所属的插件库，读 `repo.json` 决定用哪个子扫描器；
- 起进程、等它把节点目录（键 + 参数签名）报回来；
- 转发调用、代理 `ctx.call_action` 回宿主；
- 按 pluginVersionId 缓存运行时，只在某个版本第一次执行时启动一次。

宿主**不 import 插件代码**——参数签名由插件侧内省后送回来，这也是依赖不泄漏的关键。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from system.executor import wire
from system.executor.errors import NodeError

log = logging.getLogger("workflow-plugins")

DEFAULT_SCANNER = "loombot"
REPO_MANIFEST = "repo.json"
REPO_ROOT = Path(__file__).resolve().parents[2]

# 起进程 + 加载插件（可能要 import 一堆依赖）的上限。
START_TIMEOUT_SECONDS = 60.0

# 宿主 ↔ 节点进程之间是行式 JSON，值里的二进制会 base64 展开（约 +33%）。
# asyncio 子进程 stdout 的 StreamReader 默认单行上限只有 64KB（2**16），
# 节点只要返回图片这类稍大的对象，整行就会超出上限：读循环抛
# `ValueError: Separator is not found, and chunk exceed the limit` 退出，
# 进程却还活着——之后的调用写进去再也没人读，只能干等到工作流超时。
# 这里把上限抬到能覆盖引擎允许的内联载荷（见 engine.MAX_PAYLOAD_CHARS = 4MB，
# 经 base64 后约 5.4MB），留出足够余量。
NODE_MESSAGE_LIMIT = 16 * 1024 * 1024


@dataclass
class NodeDescriptor:
    """插件侧报回来的一个节点：参数签名 + 要不要注入 `ctx`。"""

    key: str
    parameters: list[dict[str, Any]]
    pass_ctx: bool


class PluginRuntime:
    """一个插件版本的节点进程。"""

    def __init__(
        self,
        plugin_version_id: int,
        plugin_key: str,
        plugin_dir: Path,
        interpreter: str,
        scanner_name: str,
        repo_folder: Path,
        artifact_sha256: str = "",
    ) -> None:
        self.plugin_version_id = plugin_version_id
        self.plugin_key = plugin_key
        self.plugin_dir = plugin_dir
        self.interpreter = interpreter
        self.scanner_name = scanner_name
        self.repo_folder = repo_folder
        self.artifact_sha256 = artifact_sha256
        self.nodes: dict[str, NodeDescriptor] = {}
        self._process: asyncio.subprocess.Process | None = None
        self._reader_task: asyncio.Task[Any] | None = None
        self._stderr_task: asyncio.Task[Any] | None = None
        self._ready: asyncio.Future[None] | None = None
        self._pending: dict[int, asyncio.Future[Any]] = {}
        # invokeId -> 那次调用的 action_caller，供 `ctx.call_action` 回宿主用
        self._callers: dict[int, Callable[..., Any]] = {}
        self._seq = 0
        self._write_lock = asyncio.Lock()
        self._start_lock = asyncio.Lock()

    async def start(self) -> None:
        """确保节点进程已就绪；已经起过就直接返回。"""
        async with self._start_lock:
            if self._process is not None and self._process.returncode is None:
                if self._reader_task is not None and not self._reader_task.done():
                    return
                # 读循环已经退出、进程却还活着：stdout 没人读，再调用只会一直等
                # 到工作流超时。主动收掉旧进程，重开一个干净的。
                log.warning(
                    "插件节点进程读循环已退出，重启进程: version=%s key=%s",
                    self.plugin_version_id,
                    self.plugin_key,
                )
                await self._terminate()
            await self._spawn()

    async def _spawn(self) -> None:
        loop = asyncio.get_running_loop()
        self._ready = loop.create_future()
        log.info(
            "启动插件节点进程: version=%s key=%s python=%s",
            self.plugin_version_id,
            self.plugin_key,
            self.interpreter,
        )
        self._process = await asyncio.create_subprocess_exec(
            self.interpreter,
            "-m",
            "system.executor.node_worker",
            "--plugin-dir",
            str(self.plugin_dir),
            "--scanner",
            self.scanner_name,
            "--repo",
            str(self.repo_folder),
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            limit=NODE_MESSAGE_LIMIT,
            cwd=str(REPO_ROOT),
            env={
                **os.environ,
                "PYTHONUNBUFFERED": "1",
                "PYTHONIOENCODING": "utf-8",
                "PYTHONUTF8": "1",
            },
        )
        self._reader_task = asyncio.create_task(self._read_loop())
        self._stderr_task = asyncio.create_task(self._stderr_loop())
        try:
            await asyncio.wait_for(self._ready, START_TIMEOUT_SECONDS)
        except (asyncio.TimeoutError, NodeError):
            await self._terminate()
            raise

    async def _read_loop(self) -> None:
        process = self._process
        if process is None or process.stdout is None:
            return
        try:
            async for raw in process.stdout:
                line = raw.decode("utf-8", "replace").strip()
                if not line:
                    continue
                try:
                    message = wire.loads(line)
                except Exception:  # noqa: BLE001 - 非协议输出只记一笔
                    log.warning("节点进程输出了非协议内容，已忽略: %s", line[:200])
                    continue
                self._dispatch(message)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - 读循环挂了要让调用方拿到原因，别干等
            log.warning(
                "插件节点进程输出读取失败（单条消息上限 %s 字节）: key=%s err=%s",
                NODE_MESSAGE_LIMIT,
                self.plugin_key,
                exc,
            )
            self._fail_all(f"插件节点进程输出读取失败：{exc}")
        finally:
            self._fail_all("插件节点进程已退出")
            if self._ready is not None and not self._ready.done():
                self._ready.set_exception(NodeError("插件节点进程启动即退出"))

    async def _stderr_loop(self) -> None:
        """插件的 print 和日志都走 stderr，原样转发到执行器日志。"""
        process = self._process
        if process is None or process.stderr is None:
            return
        async for raw in process.stderr:
            text = raw.decode("utf-8", "replace").rstrip()
            if text:
                log.info("[node:%s] %s", self.plugin_key, text)

    def _dispatch(self, message: dict[str, Any]) -> None:
        kind = str(message.get("type") or "")
        if kind == "ready":
            self._on_ready(message)
        elif kind == "result":
            self._resolve(self._pending, message)
        elif kind == "action":
            # 动作要回宿主调适配器，不能阻塞读循环。
            asyncio.create_task(self._handle_action(message))
        elif kind == "error":
            if self._ready is not None and not self._ready.done():
                self._ready.set_exception(
                    NodeError(str(message.get("message") or "插件节点加载失败"))
                )

    def _on_ready(self, message: dict[str, Any]) -> None:
        for key, item in (message.get("nodes") or {}).items():
            self.nodes[str(key)] = NodeDescriptor(
                key=str(key),
                parameters=list(item.get("parameters") or []),
                pass_ctx=bool(item.get("passCtx")),
            )
        log.info(
            "插件节点进程就绪: version=%s key=%s nodes=%s",
            self.plugin_version_id,
            self.plugin_key,
            sorted(self.nodes),
        )
        if self._ready is not None and not self._ready.done():
            self._ready.set_result(None)

    def _resolve(self, pending: dict[int, asyncio.Future[Any]], message: dict[str, Any]) -> None:
        future = pending.pop(int(message.get("id") or 0), None)
        if future is None or future.done():
            return
        if message.get("ok"):
            future.set_result(message.get("value"))
            return
        error = message.get("error") or {}
        future.set_exception(
            NodeError(
                str(error.get("message") or "节点执行失败"),
                code=str(error.get("code") or "NODE_FAILED"),
            )
        )

    def _fail_all(self, reason: str) -> None:
        for future in list(self._pending.values()):
            if not future.done():
                future.set_exception(NodeError(reason))
        self._pending.clear()
        self._callers.clear()

    async def invoke(
        self, node_key: str, ctx: Any, args: list[Any], kwargs: dict[str, Any]
    ) -> Any:
        """把一次节点调用发给插件进程，等它把结果送回来。"""
        await self.start()
        self._seq += 1
        request_id = self._seq
        future: asyncio.Future[Any] = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        self._callers[request_id] = ctx.action_caller
        try:
            await self._write(
                {
                    "type": "invoke",
                    "id": request_id,
                    "nodeKey": node_key,
                    "args": list(args),
                    "kwargs": dict(kwargs),
                    "ctx": {
                        "executionId": ctx.execution_id,
                        "traceId": ctx.trace_id,
                        "workflowId": ctx.workflow_id,
                        "workflowVersionId": ctx.workflow_version_id,
                        "nodeId": ctx.node_id,
                        "nodeKey": ctx.node_key,
                        "deadlineMs": ctx.deadline_ms,
                        "trigger": ctx.trigger,
                        "pluginKey": ctx.plugin_key,
                        "connectionId": ctx.connection_id,
                    },
                }
            )
            return await future
        finally:
            self._pending.pop(request_id, None)
            self._callers.pop(request_id, None)

    async def _handle_action(self, message: dict[str, Any]) -> None:
        """节点里的 `ctx.call_action` 落到这里：宿主替它去调适配器。"""
        action_id = int(message.get("id") or 0)
        caller = self._callers.get(int(message.get("invokeId") or 0))
        if caller is None:
            await self._write(
                {
                    "type": "action_result",
                    "id": action_id,
                    "ok": False,
                    "error": {
                        "code": "ACTION_FAILED",
                        "message": "发起动作的节点调用已经结束",
                    },
                }
            )
            return
        try:
            value = await caller(
                int(message.get("connectionId") or 0),
                str(message.get("nodeKey") or ""),
                dict(message.get("params") or {}),
            )
        except Exception as exc:  # noqa: BLE001 - 原样把分类和说明带回插件进程
            await self._write(
                {
                    "type": "action_result",
                    "id": action_id,
                    "ok": False,
                    "error": {
                        "code": str(getattr(exc, "code", "") or "ACTION_FAILED"),
                        "message": str(exc),
                    },
                }
            )
            return
        await self._write({"type": "action_result", "id": action_id, "ok": True, "value": value})

    async def _write(self, message: dict[str, Any]) -> None:
        process = self._process
        if process is None or process.returncode is not None or process.stdin is None:
            raise NodeError(f"插件 {self.plugin_key} 的节点进程不可用")
        async with self._write_lock:
            process.stdin.write((wire.dumps(message) + "\n").encode("utf-8"))
            await process.stdin.drain()

    async def _terminate(self) -> None:
        process = self._process
        self._process = None
        if process is None:
            return
        if process.returncode is None:
            try:
                process.stdin.write(b'{"type":"shutdown"}\n')
                await process.stdin.drain()
            except Exception:  # noqa: BLE001 - 关不掉就硬关
                pass
            try:
                await asyncio.wait_for(process.wait(), 3)
            except asyncio.TimeoutError:
                process.kill()
        for task in (self._reader_task, self._stderr_task):
            if task is not None:
                task.cancel()
        self._reader_task = None
        self._stderr_task = None
        self.nodes = {}

    async def stop(self) -> None:
        await self._terminate()


class PluginRegistry:
    """按 (pluginVersionId, 制品哈希) 缓存插件节点进程。"""

    def __init__(self) -> None:
        self._runtimes: dict[tuple[int, str], PluginRuntime] = {}

    async def get(
        self,
        plugin_version_id: int,
        install_path: str,
        plugin_key: str,
        python_path: str = "",
        artifact_sha256: str = "",
    ) -> PluginRuntime:
        # 缓存键带上制品哈希：同一个版本目录被原地覆盖时哈希会变，缓存自然失效，
        # 不用另开一条"通知运行时重载"的通道。
        cache_key = (plugin_version_id, artifact_sha256)
        runtime = self._runtimes.get(cache_key)
        if runtime is None:
            await self._evict_version(plugin_version_id)
            plugin_dir = Path(install_path).resolve()
            repo_folder = find_repo_folder(plugin_dir)
            scanner_name = (
                read_scanner_name(repo_folder) if repo_folder is not None else DEFAULT_SCANNER
            )
            if repo_folder is None:
                # 不在任何插件库文件夹里：按 LoomBot 自己的格式兜底，至少不会直接报"找不到库"。
                log.warning(
                    "插件目录不在插件库文件夹内，按 %s 格式加载: %s", scanner_name, plugin_dir
                )
            runtime = PluginRuntime(
                plugin_version_id=plugin_version_id,
                plugin_key=plugin_key,
                plugin_dir=plugin_dir,
                # 没有 requirements.txt 的版本没有私有 venv，用宿主解释器（就是本进程自己）。
                interpreter=python_path or sys.executable,
                scanner_name=scanner_name,
                repo_folder=repo_folder or plugin_dir,
                artifact_sha256=artifact_sha256,
            )
            self._runtimes[cache_key] = runtime
        await runtime.start()
        return runtime

    async def _evict_version(self, plugin_version_id: int) -> None:
        """同一版本的旧制品收掉：进程还活着的话会一直跑旧代码。"""
        for key, stale in list(self._runtimes.items()):
            if key[0] == plugin_version_id:
                self._runtimes.pop(key, None)
                log.info(
                    "插件制品已变化，重载节点进程: version=%s 旧哈希=%s",
                    plugin_version_id,
                    key[1] or "(空)",
                )
                await stale.stop()

    async def close(self) -> None:
        """执行器退出时收摊；进程还在的话 stdin 断开它们也会自己走。"""
        for runtime in list(self._runtimes.values()):
            await runtime.stop()
        self._runtimes.clear()


def find_repo_folder(plugin_dir: Path) -> Path | None:
    """往上找第一个含 `repo.json` 的祖先，那就是这个插件所属的插件库文件夹。

    布局是 `<库文件夹>/repo/<插件路径>`，所以插件目录的祖先里一定有库文件夹。
    库文件夹可以改名、搬家，靠 `repo.json` 认，不靠路径约定。
    """
    for candidate in (plugin_dir, *plugin_dir.parents):
        if (candidate / REPO_MANIFEST).is_file():
            return candidate
    return None


def read_scanner_name(repo_folder: Path) -> str:
    """读库声明里的子扫描器名；读不出来就按 LoomBot 自己的格式处理。"""
    manifest = repo_folder / REPO_MANIFEST
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        log.warning("读取插件库声明失败，按 %s 格式加载: %s，%s", DEFAULT_SCANNER, manifest, exc)
        return DEFAULT_SCANNER
    if not isinstance(data, dict):
        log.warning("插件库声明不是 JSON 对象，按 %s 格式加载: %s", DEFAULT_SCANNER, manifest)
        return DEFAULT_SCANNER
    return str(data.get("scanner") or DEFAULT_SCANNER).strip() or DEFAULT_SCANNER
