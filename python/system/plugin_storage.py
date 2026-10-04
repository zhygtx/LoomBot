"""插件持久化存储。

插件不应该把数据写进自己的版本目录：版本升级后目录会变化，旧目录也可能被清理。
宿主在这里提供一个稳定的 API，插件通过 ``ctx.storage`` 使用：

    await ctx.storage.connection.set("last_seq", 123)
    value = await ctx.storage.plugin.get("global_config")
    await ctx.storage.ephemeral.set("dedupe:event", 1, ttl=300)

后端有两套，按环境自动选择，插件代码完全一样：

* 配了 ``PLUGIN_STORAGE_BASE_URL``：把请求转发给 Java 内部接口。结构化状态和文件元数据在
  MySQL，跨进程锁在 Redis，文件内容在对象存储（当前是本地目录实现）。
* 没配：落在 ``PLUGIN_DATA_ROOT`` 下的原子 JSON 文件，供本机开发使用。

这个文件被两类进程导入：执行器宿主进程，以及插件自己的节点进程。节点进程可能没有
httpx（跑在插件私有 venv 里），所以 httpx 只在方法内部按需 import。
"""

from __future__ import annotations

import asyncio
import hashlib
import inspect
import json
import os
import re
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Callable, Protocol

_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f]")

PLUGIN = "PLUGIN"
CONNECTION = "CONNECTION"
EPHEMERAL = "EPHEMERAL"
_SCOPES = {PLUGIN, CONNECTION, EPHEMERAL}


class PluginStorageError(RuntimeError):
    """插件存储访问失败。"""


class PluginStorageConflict(PluginStorageError):
    """版本 CAS 冲突，调用方应读取新值后重试。"""


def storage_root() -> Path:
    """返回本机开发后端的数据根目录。"""
    raw = os.environ.get("PLUGIN_DATA_ROOT", "plugin-data")
    return Path(raw).expanduser().resolve()


def storage_base_url() -> str:
    """Java 内部存储接口地址；空串表示走本机文件后端。"""
    return os.environ.get("PLUGIN_STORAGE_BASE_URL", "").strip().rstrip("/")


def storage_control_token() -> str:
    return os.environ.get("PLUGIN_STORAGE_CONTROL_TOKEN", "loombot-dev-plugin-storage-token")


def _now_ms() -> int:
    return int(time.time() * 1000)


def _require_part(value: str, field: str) -> str:
    """作用域标识（插件 key、连接 id、锁名）。

    不维护字符白名单：中文、日文、西里尔字母、emoji 都放行，只挡真正会造成问题的东西 ——
    路径分隔符、`..`、纯点、控制字符。这样插件目录叫什么，key 就能叫什么，不会出现
    "中文目录能装、`ctx.storage` 用不了"这种半截兼容。
    """
    text = str(value or "").strip()
    if (
        not text
        or len(text) > 160
        or "/" in text
        or "\\" in text
        or ".." in text
        or not text.strip(".")
        or _CONTROL_CHARS.search(text)
    ):
        raise PluginStorageError(
            f"{field} 不能为空、不能超过 160 字符，也不能包含路径分隔符、`..` 或控制字符"
        )
    return text


def _require_key(key: str) -> str:
    """KV key 与文件引用。

    比作用域标识多允许斜杠，用来表达层级；仍然不允许 `..`、以斜杠开头和控制字符。
    """
    text = str(key or "").strip()
    if (
        not text
        or len(text) > 255
        or ".." in text
        or not text.strip(".")
        or text.startswith("/")
        or text.startswith("\\")
        or _CONTROL_CHARS.search(text)
    ):
        raise PluginStorageError(
            "key 不能为空、不能超过 255 字符，也不能以斜杠开头或包含 `..`、纯点、控制字符"
        )
    return text


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temp.write_bytes(data)
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def _read_payload(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(payload, dict):
        return None
    return payload


def _load_path_sync(path: Path) -> dict[str, Any] | None:
    payload = _read_payload(path)
    if payload is None:
        return None
    expires_at = payload.get("expiresAt")
    if isinstance(expires_at, int) and expires_at <= _now_ms():
        path.unlink(missing_ok=True)
        return None
    return payload


class _KvBackend(Protocol):
    async def read(self, key: str) -> dict[str, Any] | None: ...

    async def write(
        self,
        key: str,
        value: Any,
        ttl: float | int | None,
        expected_version: int | None,
    ) -> int: ...

    async def remove(self, key: str, expected_version: int | None) -> bool: ...

    async def scan(self, prefix: str, limit: int) -> list[dict[str, Any]]: ...


class _LocalKvBackend:
    """本机开发后端：一个 key 一个 JSON 文件，靠原子 rename 保证不出现半截内容。"""

    def __init__(self, directory: Path) -> None:
        self.directory = directory
        self._locks: dict[str, asyncio.Lock] = {}

    def _entry_path(self, key: str) -> Path:
        digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
        return self.directory / f"{digest}.json"

    def _lock(self, key: str) -> asyncio.Lock:
        lock = self._locks.get(key)
        if lock is None:
            lock = asyncio.Lock()
            self._locks[key] = lock
        return lock

    def _load(self, key: str) -> dict[str, Any] | None:
        payload = _load_path_sync(self._entry_path(key))
        if payload is None or payload.get("key") != key:
            return None
        return payload

    async def read(self, key: str) -> dict[str, Any] | None:
        async with self._lock(key):
            payload = await asyncio.to_thread(self._load, key)
        if payload is None:
            return None
        return {"value": payload.get("value"), "version": int(payload.get("version") or 0)}

    async def write(
        self,
        key: str,
        value: Any,
        ttl: float | int | None,
        expected_version: int | None,
    ) -> int:
        async with self._lock(key):

            def commit() -> int:
                payload = self._load(key)
                current = 0 if payload is None else int(payload.get("version") or 0)
                if expected_version is not None and expected_version != current:
                    raise PluginStorageConflict(
                        f"存储版本冲突: key={key} 期望={expected_version} 当前={current}"
                    )
                version = current + 1
                expires_at = None if ttl is None else _now_ms() + int(float(ttl) * 1000)
                body = {
                    "key": key,
                    "value": value,
                    "version": version,
                    "expiresAt": expires_at,
                }
                _atomic_write(
                    self._entry_path(key),
                    json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
                )
                return version

            return await asyncio.to_thread(commit)

    async def remove(self, key: str, expected_version: int | None) -> bool:
        async with self._lock(key):

            def discard() -> bool:
                payload = self._load(key)
                if payload is None:
                    return False
                current = int(payload.get("version") or 0)
                if expected_version is not None and expected_version != current:
                    raise PluginStorageConflict(
                        f"存储版本冲突: key={key} 期望={expected_version} 当前={current}"
                    )
                self._entry_path(key).unlink(missing_ok=True)
                return True

            return await asyncio.to_thread(discard)

    async def scan(self, prefix: str, limit: int) -> list[dict[str, Any]]:
        async with self._lock(f"__scan__:{prefix}:{limit}"):

            def collect() -> list[dict[str, Any]]:
                result: list[dict[str, Any]] = []
                if not self.directory.is_dir():
                    return result
                for path in sorted(self.directory.glob("*.json")):
                    payload = _load_path_sync(path)
                    if payload is None:
                        continue
                    key = str(payload.get("key") or "")
                    if not key.startswith(prefix):
                        continue
                    result.append(
                        {
                            "key": key,
                            "value": payload.get("value"),
                            "version": int(payload.get("version") or 0),
                        }
                    )
                    if len(result) >= max(1, int(limit)):
                        break
                return result

            return await asyncio.to_thread(collect)


class _FileBackend(Protocol):
    async def put(self, ref: str, data: bytes) -> str: ...

    async def get(self, ref: str) -> bytes: ...

    async def delete(self, ref: str) -> bool: ...

    async def list(self, prefix: str) -> list[str]: ...


class _LockBackend(Protocol):
    async def acquire(self, name: str, ttl_ms: int) -> str | None: ...

    async def release(self, name: str, token: str) -> bool: ...


class _LocalFileBackend:
    """本机开发后端：文件直接落在数据根目录下，引用就是相对路径。"""

    def __init__(self, base: Path) -> None:
        self.base = base.resolve()

    def _resolve(self, ref: str, *, must_exist: bool = False) -> Path:
        text = str(ref or "").replace("\\", "/").strip("/")
        if not text or ".." in text.split("/") or text.startswith("/"):
            raise PluginStorageError(f"非法文件引用: {ref}")
        target = (self.base / text).resolve()
        if not target.is_relative_to(self.base):
            raise PluginStorageError(f"文件引用越界: {ref}")
        if must_exist and not target.is_file():
            raise PluginStorageError(f"文件不存在: {ref}")
        return target

    async def put(self, ref: str, data: bytes) -> str:
        target = self._resolve(ref)
        await asyncio.to_thread(_atomic_write, target, data)
        return target.relative_to(self.base).as_posix()

    async def get(self, ref: str) -> bytes:
        target = self._resolve(ref, must_exist=True)
        return await asyncio.to_thread(target.read_bytes)

    async def delete(self, ref: str) -> bool:
        target = self._resolve(ref)
        if not target.is_file():
            return False
        await asyncio.to_thread(target.unlink)
        return True

    async def list(self, prefix: str) -> list[str]:
        text = str(prefix or "").replace("\\", "/").strip("/")
        start = self.base if not text else self._resolve(text)
        if not start.is_dir():
            return []

        def scan() -> list[str]:
            return sorted(
                path.relative_to(self.base).as_posix()
                for path in start.rglob("*")
                if path.is_file()
            )

        return await asyncio.to_thread(scan)


class _LocalLockBackend:
    """本机开发后端：用 ``O_CREAT|O_EXCL`` 建锁文件，靠 mtime 判断是否过期。"""

    def __init__(self, directory: Path) -> None:
        self.directory = directory

    def _path(self, name: str) -> Path:
        return self.directory / f"{hashlib.sha256(name.encode('utf-8')).hexdigest()}.lock"

    async def acquire(self, name: str, ttl_ms: int) -> str | None:
        await asyncio.to_thread(self.directory.mkdir, parents=True, exist_ok=True)
        path = self._path(name)
        token = uuid.uuid4().hex

        def attempt() -> str | None:
            try:
                fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            except FileExistsError:
                try:
                    age_ms = (time.time() - path.stat().st_mtime) * 1000
                except OSError:
                    age_ms = 0
                if age_ms >= max(1000, ttl_ms):
                    path.unlink(missing_ok=True)
                return None
            try:
                os.write(fd, token.encode("ascii"))
            finally:
                os.close(fd)
            return token

        return await asyncio.to_thread(attempt)

    async def release(self, name: str, token: str) -> bool:
        path = self._path(name)

        def discard() -> bool:
            try:
                if path.read_text(encoding="ascii") != token:
                    return False
                path.unlink(missing_ok=True)
                return True
            except OSError:
                return False

        return await asyncio.to_thread(discard)


class _HttpTransport:
    """Java 内部存储接口的共享客户端。

    httpx 只在方法内部 import：节点进程跑在插件私有 venv 里，可能根本没装它，
    而它们只会用到本机文件后端。
    """

    def __init__(self, base_url: str, token: str, scope_segment: str) -> None:
        self.base_url = base_url
        self.token = token
        self.scope_path = f"{base_url}/{scope_segment}"

    async def request(
        self,
        method: str,
        url: str,
        *,
        params: dict[str, Any] | None = None,
        json_body: Any = None,
        content: bytes | None = None,
        content_type: str | None = None,
        timeout: float = 15.0,
    ) -> Any:
        import httpx

        headers = {"X-Plugin-Storage-Token": self.token}
        if content_type:
            headers["Content-Type"] = content_type
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                return await client.request(
                    method,
                    url,
                    headers=headers,
                    params=params,
                    json=json_body,
                    content=content,
                )
        except Exception as exc:  # noqa: BLE001 - 控制面不可达统一分类
            detail = str(exc).strip() or type(exc).__name__
            raise PluginStorageError(
                f"插件存储接口不可达（{type(exc).__name__}）: {detail}"
            ) from exc

    @staticmethod
    def failure(response: Any) -> PluginStorageError:
        """把非预期状态码翻成异常；409 单独成类，供 update 的重试逻辑识别。"""
        message = ""
        try:
            body = response.json()
            if isinstance(body, dict):
                # 本接口自己的错误体用 error；落到全局兜底处理器时是 message
                message = str(body.get("error") or body.get("message") or "")
        except Exception:  # noqa: BLE001 - 出错响应不一定是 JSON
            message = response.text[:200]
        if response.status_code == 409:
            return PluginStorageConflict(message or "存储版本冲突")
        return PluginStorageError(f"插件存储接口返回 {response.status_code}: {message}")


class _HttpKvBackend:
    """结构化状态后端：真相在 MySQL，接口由 Java 提供。"""

    def __init__(self, transport: _HttpTransport) -> None:
        self.transport = transport

    async def read(self, key: str) -> dict[str, Any] | None:
        response = await self.transport.request(
            "GET", f"{self.transport.scope_path}/kv/entry", params={"key": key}
        )
        if response.status_code == 404:
            return None
        if response.status_code != 200:
            raise self.transport.failure(response)
        body = response.json()
        return {"value": body.get("value"), "version": int(body.get("version") or 0)}

    async def write(
        self,
        key: str,
        value: Any,
        ttl: float | int | None,
        expected_version: int | None,
    ) -> int:
        response = await self.transport.request(
            "PUT",
            f"{self.transport.scope_path}/kv/entry",
            params={"key": key},
            json_body={
                "value": value,
                "ttlSeconds": None if ttl is None else float(ttl),
                "expectedVersion": expected_version,
            },
        )
        if response.status_code != 200:
            raise self.transport.failure(response)
        return int(response.json().get("version") or 0)

    async def remove(self, key: str, expected_version: int | None) -> bool:
        response = await self.transport.request(
            "DELETE",
            f"{self.transport.scope_path}/kv/entry",
            params={"key": key, "expectedVersion": expected_version},
        )
        if response.status_code != 200:
            raise self.transport.failure(response)
        return bool(response.json().get("deleted"))

    async def scan(self, prefix: str, limit: int) -> list[dict[str, Any]]:
        response = await self.transport.request(
            "GET",
            f"{self.transport.scope_path}/kv/keys",
            params={"prefix": prefix, "limit": max(1, int(limit))},
        )
        if response.status_code != 200:
            raise self.transport.failure(response)
        body = response.json()
        if not isinstance(body, list):
            raise PluginStorageError("插件存储接口返回的 key 列表格式不合法")
        return [
            {
                "key": str(item.get("key")),
                "value": item.get("value"),
                "version": int(item.get("version") or 0),
            }
            for item in body
            if isinstance(item, dict) and item.get("key")
        ]


class _HttpFileBackend:
    """文件后端：元数据在 MySQL，内容在对象存储，插件只看到逻辑引用。"""

    def __init__(self, transport: _HttpTransport) -> None:
        self.transport = transport

    async def put(self, ref: str, data: bytes) -> str:
        response = await self.transport.request(
            "PUT",
            f"{self.transport.scope_path}/files/entry",
            params={"ref": ref},
            content=data,
            content_type="application/octet-stream",
            timeout=60.0,
        )
        if response.status_code != 200:
            raise self.transport.failure(response)
        return str(response.json().get("ref") or ref)

    async def get(self, ref: str) -> bytes:
        response = await self.transport.request(
            "GET",
            f"{self.transport.scope_path}/files/entry",
            params={"ref": ref},
            timeout=60.0,
        )
        if response.status_code == 404:
            raise PluginStorageError(f"文件不存在: {ref}")
        if response.status_code != 200:
            raise self.transport.failure(response)
        return response.content

    async def delete(self, ref: str) -> bool:
        response = await self.transport.request(
            "DELETE",
            f"{self.transport.scope_path}/files/entry",
            params={"ref": ref},
        )
        if response.status_code != 200:
            raise self.transport.failure(response)
        return bool(response.json().get("deleted"))

    async def list(self, prefix: str) -> list[str]:
        response = await self.transport.request(
            "GET",
            f"{self.transport.scope_path}/files/keys",
            params={"prefix": prefix, "limit": 1000},
        )
        if response.status_code != 200:
            raise self.transport.failure(response)
        body = response.json()
        if not isinstance(body, list):
            raise PluginStorageError("插件存储接口返回的文件列表格式不合法")
        return [str(item.get("ref")) for item in body if isinstance(item, dict) and item.get("ref")]


class _HttpLockBackend:
    """跨进程锁后端：真正的互斥由 Redis 提供，等待循环在 PluginStorage.lock 里。"""

    def __init__(self, transport: _HttpTransport) -> None:
        self.transport = transport

    async def acquire(self, name: str, ttl_ms: int) -> str | None:
        response = await self.transport.request(
            "POST",
            f"{self.transport.scope_path}/locks/acquire",
            params={"name": name, "ttlMs": int(ttl_ms)},
        )
        # 423 Locked 表示锁已被别人持有，是正常竞争结果，不是错误
        if response.status_code == 423:
            return None
        if response.status_code != 200:
            raise self.transport.failure(response)
        return str(response.json().get("token") or "") or None

    async def release(self, name: str, token: str) -> bool:
        response = await self.transport.request(
            "POST",
            f"{self.transport.scope_path}/locks/release",
            params={"name": name, "token": token},
        )
        if response.status_code != 200:
            raise self.transport.failure(response)
        return bool(response.json().get("released"))


def _require_file_ref(ref: str) -> str:
    """文件引用是路径形态，比 key 多一层"不能越界"的约束。"""
    text = str(ref or "").replace("\\", "/").strip()
    if not text or text.startswith("/") or ".." in text.split("/"):
        raise PluginStorageError(f"非法文件引用: {ref}")
    return text


class PluginFileStore:
    """一个作用域下的文件存储。引用是逻辑相对路径，不是宿主机绝对路径。"""

    def __init__(self, backend: _FileBackend) -> None:
        self._backend = backend

    async def put(self, ref: str, data: bytes | str) -> str:
        payload = data.encode("utf-8") if isinstance(data, str) else bytes(data)
        return await self._backend.put(_require_file_ref(ref), payload)

    async def get(self, ref: str) -> bytes:
        return await self._backend.get(_require_file_ref(ref))

    async def delete(self, ref: str) -> bool:
        return await self._backend.delete(_require_file_ref(ref))

    async def list(self, prefix: str = "") -> list[str]:
        text = "" if not prefix else _require_file_ref(prefix)
        return await self._backend.list(text)


class PluginStorage:
    """一个固定作用域的键值存储，外加文件与锁。"""

    def __init__(
        self,
        plugin_key: str,
        scope: str,
        scope_id: str = "0",
        root: Path | None = None,
    ) -> None:
        if scope not in _SCOPES:
            raise PluginStorageError(f"未知存储作用域: {scope}")
        self.plugin_key = _require_part(plugin_key, "plugin_key")
        self.scope = scope
        self.scope_id = _require_part(scope_id or "0", "scope_id")

        base_url = storage_base_url()
        if base_url:
            segment = f"{self.plugin_key}/{scope}/{self.scope_id}"
            transport = _HttpTransport(base_url, storage_control_token(), segment)
            self._kv: _KvBackend = _HttpKvBackend(transport)
            self._locks: _LockBackend = _HttpLockBackend(transport)
            self.files = PluginFileStore(_HttpFileBackend(transport))
        else:
            base = (root or storage_root()) / self.plugin_key / scope / self.scope_id
            self._kv = _LocalKvBackend(base / "kv")
            self._locks = _LocalLockBackend(base / "locks")
            self.files = PluginFileStore(_LocalFileBackend(base / "files"))

    async def get(self, key: str, default: Any = None) -> Any:
        record = await self._kv.read(_require_key(key))
        return default if record is None else record.get("value")

    async def version(self, key: str) -> int | None:
        record = await self._kv.read(_require_key(key))
        return None if record is None else int(record.get("version") or 0)

    async def set(
        self,
        key: str,
        value: Any,
        *,
        ttl: float | int | None = None,
        expected_version: int | None = None,
    ) -> int:
        """写入 JSON 值并返回新版本号。

        传入 ``expected_version`` 时做乐观锁：版本不一致抛 ``PluginStorageConflict``。
        ``expected_version=0`` 表示只允许新建。
        """
        return await self._kv.write(_require_key(key), value, ttl, expected_version)

    async def delete(self, key: str, *, expected_version: int | None = None) -> bool:
        return await self._kv.remove(_require_key(key), expected_version)

    async def keys(self, prefix: str = "") -> list[str]:
        normalized = "" if not prefix else _require_key(prefix)
        rows = await self._kv.scan(normalized, 1000)
        return [str(row["key"]) for row in rows]

    async def list(self, prefix: str = "", *, limit: int = 1000) -> list[dict[str, Any]]:
        normalized = "" if not prefix else _require_key(prefix)
        return await self._kv.scan(normalized, max(1, int(limit)))

    async def update(
        self,
        key: str,
        updater: Callable[[Any, int], Any] | Callable[[Any], Any],
        *,
        ttl: float | int | None = None,
        retries: int = 5,
    ) -> int:
        """用读改写更新值；发生并发冲突时重试。"""
        normalized = _require_key(key)
        for _ in range(max(1, int(retries))):
            current = await self.get(normalized)
            current_version = await self.version(normalized)
            version = 0 if current_version is None else current_version
            try:
                parameters = len(inspect.signature(updater).parameters)
            except (TypeError, ValueError):
                parameters = 1
            next_value = updater(current, version) if parameters >= 2 else updater(current)
            if inspect.isawaitable(next_value):
                next_value = await next_value
            try:
                return await self.set(
                    normalized,
                    next_value,
                    ttl=ttl,
                    expected_version=version,
                )
            except PluginStorageConflict:
                await asyncio.sleep(0.01)
        raise PluginStorageConflict(f"更新重试次数已用完: key={normalized}")

    @asynccontextmanager
    async def lock(self, name: str, *, ttl: float = 30.0, wait: float = 30.0):
        """获取一个跨进程锁；适合包住短小的初始化或迁移逻辑。

        等待重试放在这里而不是宿主接口里：宿主的 acquire 是单次尝试，
        等待期间不占用服务端线程。
        """
        lock_name = _require_part(name, "lock_name")
        ttl_ms = max(1000, int(max(1.0, float(ttl)) * 1000))
        deadline = time.monotonic() + max(0.0, float(wait))
        token: str | None = None
        while token is None:
            token = await self._locks.acquire(lock_name, ttl_ms)
            if token is not None:
                break
            if time.monotonic() >= deadline:
                raise PluginStorageError(f"锁等待超时: {lock_name}")
            await asyncio.sleep(0.05)
        try:
            yield
        finally:
            try:
                await self._locks.release(lock_name, token)
            except Exception:  # noqa: BLE001 - 释放失败不应掩盖临界区里的异常
                pass


class PluginStorageHub:
    """插件看到的三种作用域入口。"""

    def __init__(
        self,
        plugin_key: str,
        *,
        connection_id: int | None = None,
        root: Path | None = None,
    ) -> None:
        key = _require_part(plugin_key, "plugin_key")
        connected = connection_id is not None and int(connection_id) > 0
        self.plugin = PluginStorage(key, PLUGIN, "0", root)
        self.connection = (
            PluginStorage(key, CONNECTION, str(connection_id), root) if connected else None
        )
        self.ephemeral = PluginStorage(
            key,
            EPHEMERAL,
            str(connection_id) if connected else "0",
            root,
        )


def storage_for(plugin_key: str, connection_id: int | None = None) -> PluginStorageHub:
    return PluginStorageHub(plugin_key, connection_id=connection_id)
