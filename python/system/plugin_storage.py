"""插件持久化存储。

插件不应该把数据写进自己的版本目录：版本升级后目录会变化，旧目录也可能被清理。
宿主在这里提供一个稳定的 API，插件通过 ``ctx.storage`` 使用：

    await ctx.storage.connection.set("last_seq", 123)
    value = await ctx.storage.plugin.get("global_config")
    await ctx.storage.ephemeral.set("dedupe:event", 1, ttl=300)

当前后端是宿主共享目录下的原子 JSON 文件。接口按可替换后端设计，
以后换成 MySQL/Redis 不需要修改插件代码。
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
from typing import Any, Awaitable, Callable, Iterator

_SAFE_PART = re.compile(r"^[A-Za-z0-9._:-]{1,160}$")
_KEY_CHARS = re.compile(r"^[A-Za-z0-9._:/\\-]{1,255}$")

PLUGIN = "PLUGIN"
CONNECTION = "CONNECTION"
EPHEMERAL = "EPHEMERAL"
_SCOPES = {PLUGIN, CONNECTION, EPHEMERAL}


class PluginStorageError(RuntimeError):
    """插件存储访问失败。"""


class PluginStorageConflict(PluginStorageError):
    """版本 CAS 冲突，调用方应读取新值后重试。"""


def storage_root() -> Path:
    """返回宿主配置的插件数据根目录。"""
    raw = os.environ.get("PLUGIN_DATA_ROOT", "plugin-data")
    return Path(raw).expanduser().resolve()


def _now_ms() -> int:
    return int(time.time() * 1000)


def _require_part(value: str, field: str) -> str:
    text = str(value or "").strip()
    if not _SAFE_PART.fullmatch(text):
        raise PluginStorageError(
            f"{field} 只能包含字母、数字、点、下划线、冒号和短横线，且不超过 160 字符"
        )
    return text


def _require_key(key: str) -> str:
    text = str(key or "").strip()
    if (
        not text
        or len(text) > 255
        or not _KEY_CHARS.fullmatch(text)
        or ".." in text
        or text.startswith("/")
    ):
        raise PluginStorageError(
            "key 只能包含字母、数字、点、下划线、冒号、短横线和斜杠，且不能越界"
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


class PluginStorage:
    """一个固定作用域的 JSON 键值存储。"""

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
        self.root = (root or storage_root()).resolve()
        self._base = self.root / self.plugin_key / scope / self.scope_id
        self._kv_dir = self._base / "kv"
        self._lock_dir = self._base / "locks"
        self.files = PluginFileStore(self._base / "files")
        self._locks: dict[str, asyncio.Lock] = {}

    def _entry_path(self, key: str) -> Path:
        digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
        return self._kv_dir / f"{digest}.json"

    def _lock(self, key: str) -> asyncio.Lock:
        lock = self._locks.get(key)
        if lock is None:
            lock = asyncio.Lock()
            self._locks[key] = lock
        return lock

    def _load(self, key: str) -> tuple[dict[str, Any] | None, str]:
        path = self._entry_path(key)
        payload = _load_path_sync(path)
        if payload is None:
            return None, str(path)
        if payload.get("key") != key:
            return None, str(path)
        return payload, str(path)

    async def get(self, key: str, default: Any = None) -> Any:
        normalized = _require_key(key)
        async with self._lock(normalized):
            payload, _ = await asyncio.to_thread(self._load, normalized)
        return default if payload is None else payload.get("value")

    async def version(self, key: str) -> int | None:
        normalized = _require_key(key)
        async with self._lock(normalized):
            payload, _ = await asyncio.to_thread(self._load, normalized)
        return None if payload is None else int(payload.get("version") or 0)

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

        normalized = _require_key(key)
        async with self._lock(normalized):

            def write() -> int:
                payload, path_text = self._load(normalized)
                current_version = 0 if payload is None else int(payload.get("version") or 0)
                if expected_version is not None and expected_version != current_version:
                    raise PluginStorageConflict(
                        f"存储版本冲突: key={normalized} 期望={expected_version} 当前={current_version}"
                    )
                next_version = current_version + 1
                expires_at = None if ttl is None else _now_ms() + int(float(ttl) * 1000)
                body = {
                    "key": normalized,
                    "value": value,
                    "version": next_version,
                    "expiresAt": expires_at,
                }
                _atomic_write(
                    Path(path_text),
                    json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
                )
                return next_version

            return await asyncio.to_thread(write)

    async def delete(self, key: str, *, expected_version: int | None = None) -> bool:
        normalized = _require_key(key)
        async with self._lock(normalized):

            def remove() -> bool:
                payload, path_text = self._load(normalized)
                if payload is None:
                    return False
                current_version = int(payload.get("version") or 0)
                if expected_version is not None and expected_version != current_version:
                    raise PluginStorageConflict(
                        f"存储版本冲突: key={normalized} 期望={expected_version} 当前={current_version}"
                    )
                Path(path_text).unlink(missing_ok=True)
                return True

            return await asyncio.to_thread(remove)

    async def keys(self, prefix: str = "") -> list[str]:
        normalized_prefix = "" if not prefix else _require_key(prefix)
        async with self._lock(f"__keys__:{normalized_prefix}"):

            def scan() -> list[str]:
                result: list[str] = []
                if not self._kv_dir.is_dir():
                    return result
                for path in self._kv_dir.glob("*.json"):
                    payload = _load_path_sync(path)
                    if payload is None:
                        continue
                    key = str(payload.get("key") or "")
                    if key.startswith(normalized_prefix):
                        result.append(key)
                return sorted(result)

            return await asyncio.to_thread(scan)

    async def list(self, prefix: str = "", *, limit: int = 1000) -> list[dict[str, Any]]:
        normalized_prefix = "" if not prefix else _require_key(prefix)
        async with self._lock(f"__list__:{normalized_prefix}"):

            def scan() -> list[dict[str, Any]]:
                result: list[dict[str, Any]] = []
                if not self._kv_dir.is_dir():
                    return result
                for path in sorted(self._kv_dir.glob("*.json")):
                    payload = _load_path_sync(path)
                    if payload is None:
                        continue
                    key = str(payload.get("key") or "")
                    if not key.startswith(normalized_prefix):
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

            return await asyncio.to_thread(scan)

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
        """获取一个跨进程文件锁；适合包住短小的初始化或迁移逻辑。"""
        lock_name = _require_part(name, "lock_name")
        self._lock_dir.mkdir(parents=True, exist_ok=True)
        path = self._lock_dir / f"{hashlib.sha256(lock_name.encode()).hexdigest()}.lock"
        token = uuid.uuid4().hex
        deadline = time.monotonic() + max(0.0, float(wait))
        while True:
            try:
                fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.write(fd, token.encode("ascii"))
                os.close(fd)
                break
            except FileExistsError:
                try:
                    age = time.time() - path.stat().st_mtime
                except OSError:
                    age = 0
                if age >= max(1.0, float(ttl)):
                    path.unlink(missing_ok=True)
                    continue
                if time.monotonic() >= deadline:
                    raise PluginStorageError(f"锁等待超时: {lock_name}")
                await asyncio.sleep(0.05)
        try:
            yield
        finally:
            try:
                if path.read_text(encoding="ascii") == token:
                    path.unlink(missing_ok=True)
            except OSError:
                pass

class PluginFileStore:
    """一个作用域下的文件存储。引用是相对路径，不是宿主机绝对路径。"""

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

    async def put(self, ref: str, data: bytes | str) -> str:
        target = self._resolve(ref)
        payload = data.encode("utf-8") if isinstance(data, str) else bytes(data)
        await asyncio.to_thread(_atomic_write, target, payload)
        return target.relative_to(self.base).as_posix()

    async def get(self, ref: str) -> bytes:
        target = self._resolve(ref, must_exist=True)
        return await asyncio.to_thread(target.read_bytes)

    async def delete(self, ref: str) -> bool:
        target = self._resolve(ref)
        existed = target.is_file()
        await asyncio.to_thread(target.unlink, True)
        return existed

    async def list(self, prefix: str = "") -> list[str]:
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
        self.plugin = PluginStorage(key, PLUGIN, "0", root)
        self.connection = (
            PluginStorage(key, CONNECTION, str(connection_id), root)
            if connection_id is not None and int(connection_id) > 0
            else None
        )
        self.ephemeral = PluginStorage(
            key,
            EPHEMERAL,
            str(connection_id) if connection_id is not None and int(connection_id) > 0 else "0",
            root,
        )


def storage_for(plugin_key: str, connection_id: int | None = None) -> PluginStorageHub:
    return PluginStorageHub(plugin_key, connection_id=connection_id)
