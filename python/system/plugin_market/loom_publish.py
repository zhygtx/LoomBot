#!/usr/bin/env python3
"""Loom 插件库发布脚本（check / build / start，纯标准库，Python 3.11+）。

用法（都在插件库根目录执行）：

  python scripts/loom_publish.py check sources/<作者>/<key>
      贡献者自检：plugin.toml / 目录整洁 / AST 语法 / 命名一致性 / 适配器声明 / 版本目录未被占用。

  python scripts/loom_publish.py build sources/<作者>/<key> --apply . [--clean-source]
      维护者或机器人发布：复制到 plugins/<作者>/<key>/<版本>/ 并合并进 index.json。
      --clean-source 会在发布成功后删掉 sources 里那份暂存源码（机器人用）。

  python scripts/loom_publish.py start <作者>/<key> [--library .]
      要接着改某个已发布的插件：把最新版本复制回 sources/，然后改版本号再提交。

约定（三条，缺一不可）：

1. **sources/ 是暂存区，不是长期源码目录。** 作者在这里提交，PR 通过后由机器人复制进
   plugins/ 并清理掉——所以作者每次面对的 sources/ 都是干净的，不用在一堆别人的插件里找自己那份。
   要更新就 `start` 把最新版本复制回来。
2. **作者目录名 = namespace = plugin.toml 的 author，三者必须一致。** namespace 会拼进
   plugin_key（`<作者>.<key>`），这是不同作者用同名插件的唯一隔离手段——不一致会让同步直接撞唯一键。
3. **版本目录一旦发布就不可变。** 改代码要开新版本号；目标版本目录已存在会被 check 拦下。

退出码：0 成功 / 1 校验失败 / 2 参数错误。
"""

from __future__ import annotations

import argparse
import ast
import json
import re
import shutil
import sys
import tomllib
from datetime import datetime
from pathlib import Path

NAME_MAX_LEN = 64
INVALID_SEGMENT_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
WINDOWS_RESERVED = frozenset(
    {
        "con",
        "prn",
        "aux",
        "nul",
        *(f"com{i}" for i in range(1, 10)),
        *(f"lpt{i}" for i in range(1, 10)),
    }
)
# Loom 自己有这两个概念，插件键占用会让人分不清
RESERVED_SEGMENTS = frozenset({"local", "system"})
BANNED_DIR_NAMES = frozenset({"data", "__pycache__", "_deps", "venv", ".venv", ".git"})
SKIP_COPY_NAMES = frozenset(
    {"__pycache__", ".git", ".idea", ".vscode", ".venv", "venv", "_deps", "data", ".DS_Store"}
)
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$")
INDEX_SCHEMA_VERSION = 1
MANIFEST_NAME = "plugin.toml"


class PublishError(Exception):
    """校验失败：消息直接给作者看。"""


# ---------------------------------------------------------------------------
# 基础校验
# ---------------------------------------------------------------------------


def validate_segment(value: str, *, label: str) -> str:
    """校验单个路径段（作者名或插件键）。"""
    text = str(value or "").strip()
    if not text:
        raise PublishError(f"{label}不能为空")
    if len(text) > NAME_MAX_LEN:
        raise PublishError(f"{label}不能超过 {NAME_MAX_LEN} 个字符")
    if text in {".", ".."}:
        raise PublishError(f"{label}不合法")
    if text != text.strip(" .") or text.endswith("."):
        raise PublishError(f"{label}不能以空格或点结尾")
    if INVALID_SEGMENT_CHARS.search(text):
        raise PublishError(f'{label}包含不允许的字符（禁止 \\ / : * ? " < > | 与控制字符）')
    if text.casefold() in WINDOWS_RESERVED:
        raise PublishError(f"{label}不能使用 Windows 保留名称")
    if text.casefold() in RESERVED_SEGMENTS:
        raise PublishError(f"{label}不能使用保留名 {sorted(RESERVED_SEGMENTS)}")
    return text


def load_manifest(src: Path) -> dict:
    path = src / MANIFEST_NAME
    if not path.is_file():
        raise PublishError(f"缺少 {MANIFEST_NAME}")
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except (tomllib.TOMLDecodeError, OSError) as exc:
        raise PublishError(f"{MANIFEST_NAME} 解析失败：{exc}") from exc
    if not isinstance(data, dict):
        raise PublishError(f"{MANIFEST_NAME} 必须是 TOML 表")
    return data


def validate_manifest(meta: dict, *, expected_author: str, expected_key: str) -> dict:
    """校验 [plugin] 段，并返回规范化后的字段。"""
    plugin = meta.get("plugin")
    if not isinstance(plugin, dict):
        raise PublishError(f"{MANIFEST_NAME} 缺少 [plugin] 段")
    for field in ("key", "name", "version", "description", "author"):
        value = plugin.get(field)
        if not isinstance(value, str) or not value.strip():
            raise PublishError(f"[plugin] 缺少必填字段 {field}")
    key = validate_segment(plugin["key"], label="插件键 key")
    author = validate_segment(plugin["author"], label="作者 author")
    if key != expected_key:
        raise PublishError(f"[plugin] key={key!r} 与目录名 {expected_key!r} 不一致")
    if author != expected_author:
        raise PublishError(f"[plugin] author={author!r} 与作者目录名 {expected_author!r} 不一致")
    version = str(plugin["version"]).strip()
    if not VERSION_RE.match(version):
        raise PublishError(f"版本号 {version!r} 不是 X.Y.Z 形式（可带 -预发布 / +构建 后缀）")
    return {"key": key, "author": author, "version": version, "name": str(plugin["name"])}


def find_banned(src: Path) -> list[str]:
    """返回违规目录（data/ __pycache__ _deps 等）的相对路径。"""
    problems: list[str] = []
    for path in src.rglob("*"):
        if path.is_dir() and path.name in BANNED_DIR_NAMES:
            problems.append(path.relative_to(src).as_posix() + "/")
    return problems


def scan_syntax(src: Path) -> int:
    """所有 .py 过一遍 AST，只查语法，不执行代码。"""
    files = [
        path
        for path in sorted(src.rglob("*.py"))
        if not any(part in BANNED_DIR_NAMES or part.startswith(".") for part in path.relative_to(src).parts)
    ]
    errors: list[str] = []
    for path in files:
        try:
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        except SyntaxError as exc:
            errors.append(f"{path.relative_to(src).as_posix()}：{exc.msg}（第 {exc.lineno} 行）")
        except OSError as exc:
            errors.append(f"{path.relative_to(src).as_posix()}：读取失败（{exc}）")
    if errors:
        raise PublishError("Python 语法错误：\n  - " + "\n  - ".join(errors))
    return len(files)


def check_adapters(meta: dict, src: Path) -> int:
    """适配器声明里的每个文件都必须真的存在——声明和实现分成两份真相是最常见的坑。"""
    adapters = meta.get("adapters") or []
    if not isinstance(adapters, list):
        raise PublishError("[[adapters]] 必须是数组")
    for index, adapter in enumerate(adapters, 1):
        if not isinstance(adapter, dict):
            raise PublishError(f"[[adapters]] 第 {index} 项必须是表")
        adapter_type = str(adapter.get("type") or "").strip()
        if not adapter_type:
            raise PublishError(f"[[adapters]] 第 {index} 项缺少 type")
        entry = str(adapter.get("entry") or "").strip()
        if not entry:
            raise PublishError(f"适配器 {adapter_type} 缺少 entry")
        required = [(entry, "entry")]
        schema = str(adapter.get("connection_schema") or "").strip()
        if schema:
            required.append((schema, "connection_schema"))
        for field in ("events_dir", "actions_dir"):
            value = str(adapter.get(field) or "").strip()
            if value:
                required.append((value, field))
        for relative, label in required:
            if not (src / relative).exists():
                raise PublishError(f"适配器 {adapter_type} 的 {label} 指向的文件不存在：{relative}")
        # connection_schema 里的 x-direction 是 Loom 扫描时必读的；缺了会在同步阶段才报错，
        # 那时候已经发布了，所以这里提前拦一道。
        if schema:
            schema_path = src / schema
            try:
                data = json.loads(schema_path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError) as exc:
                raise PublishError(f"适配器 {adapter_type} 的 {schema} 解析失败：{exc}") from exc
            direction = str((data or {}).get("x-direction") or "").strip().upper()
            if direction not in {"FORWARD", "REVERSE"}:
                raise PublishError(
                    f"适配器 {adapter_type} 的 {schema} 缺少 x-direction"
                    "（必须是 FORWARD 或 REVERSE）"
                )
    return len(adapters)


# ---------------------------------------------------------------------------
# index.json
# ---------------------------------------------------------------------------


def load_index(root: Path) -> dict:
    path = root / "index.json"
    if not path.is_file():
        raise PublishError(f"插件库根目录缺少 index.json：{root}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        raise PublishError(f"index.json 解析失败：{exc}") from exc
    if not isinstance(data, dict):
        raise PublishError("index.json 必须是 JSON 对象")
    if data.get("schemaVersion") != INDEX_SCHEMA_VERSION:
        raise PublishError(f"index.json 的 schemaVersion 不是 {INDEX_SCHEMA_VERSION}，拒绝合并")
    if not isinstance(data.get("plugins"), list):
        raise PublishError("index.json 的 plugins 必须是数组")
    return data


def save_index(root: Path, data: dict) -> None:
    """原子写：先写临时文件再替换，中途失败不会留下半个 index.json。"""
    path = root / "index.json"
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def find_entry(data: dict, namespace: str, key: str) -> dict | None:
    for entry in data["plugins"]:
        if not isinstance(entry, dict):
            continue
        if str(entry.get("namespace") or "") == namespace and str(entry.get("key") or "") == key:
            return entry
    return None


def version_sort_key(version: str) -> tuple:
    """把 X.Y.Z[-pre] 变成可比较的元组；预发布排在正式版之前。"""
    core, _, pre = version.partition("-")
    core = core.partition("+")[0]
    parts = tuple(int(piece) if piece.isdigit() else 0 for piece in core.split(".")[:3])
    return (*parts, 0 if pre else 1)


def merge_version(root: Path, *, author: str, key: str, version: str, path: str) -> None:
    data = load_index(root)
    entry = find_entry(data, author, key)
    if entry is None:
        entry = {"key": key, "namespace": author, "versions": []}
        data["plugins"].append(entry)
    versions = entry.setdefault("versions", [])
    if not isinstance(versions, list):
        raise PublishError(f"index.json 里 {author}/{key} 的 versions 不是数组")
    for item in versions:
        if isinstance(item, dict) and str(item.get("version") or "") == version:
            raise PublishError(f"index.json 里已有 {author}/{key} v{version}（版本不可原地覆盖，请升版本号）")
    versions.append(
        {
            "version": version,
            "path": path,
            "publishedTime": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        }
    )
    versions.sort(key=lambda item: version_sort_key(str(item.get("version") or "0")))
    data["plugins"].sort(key=lambda item: (str(item.get("namespace") or ""), str(item.get("key") or "")))
    save_index(root, data)


# ---------------------------------------------------------------------------
# 路径推断
# ---------------------------------------------------------------------------


def library_root_of(src: Path) -> Path | None:
    """从 `.../sources/<作者>/<key>` 反推库根目录；不像就返回 None。"""
    resolved = src.resolve()
    if resolved.parent.parent.name != "sources":
        return None
    return resolved.parent.parent.parent


def expected_names(src: Path) -> tuple[str, str]:
    """作者目录名与插件目录名。"""
    if src.parent.name == "" or src.name == "":
        raise PublishError(f"源码目录必须是 sources/<作者>/<插件键> 的形式：{src}")
    return src.parent.name, src.name


# ---------------------------------------------------------------------------
# 命令
# ---------------------------------------------------------------------------


def run_check(src: Path, *, library_root: Path | None) -> dict:
    if not src.is_dir():
        raise PublishError(f"源码目录不存在：{src}")
    author, key = expected_names(src)
    print(f"== 自检 {author}/{key} ==")

    meta = load_manifest(src)
    info = validate_manifest(meta, expected_author=author, expected_key=key)
    print(f"  ✓ plugin.toml 通过（{info['name']} v{info['version']}）")

    problems = find_banned(src)
    if problems:
        raise PublishError("源码目录包含违规目录（禁止提交）：" + ", ".join(problems))
    print("  ✓ 目录整洁（无 data/ __pycache__ _deps 等）")

    count = scan_syntax(src)
    print(f"  ✓ AST 语法通过（{count} 个 .py）")

    adapters = check_adapters(meta, src)
    print(f"  ✓ 适配器声明通过（{adapters} 个）" if adapters else "  ✓ 无适配器声明")

    root = library_root or library_root_of(src)
    if root is not None:
        target = root / "plugins" / author / key / info["version"]
        if target.exists():
            raise PublishError(
                f"目标版本目录已存在：{target.relative_to(root).as_posix()}"
                "（版本不可原地覆盖，请升版本号）"
            )
        # index 也查一遍：先复制再合并的话，合并失败会在库里留下一个没登记的孤儿版本目录。
        entry = find_entry(load_index(root), author, key)
        listed = [
            str(item.get("version") or "")
            for item in (entry or {}).get("versions") or []
            if isinstance(item, dict)
        ]
        if info["version"] in listed:
            raise PublishError(
                f"index.json 里已有 {author}/{key} v{info['version']}（版本不可原地覆盖，请升版本号）"
            )
        print(f"  ✓ 目标版本目录未被占用（plugins/{author}/{key}/{info['version']}/）")

    print("== 自检通过 ==")
    return info


def run_build(src: Path, *, library_root: Path, clean_source: bool) -> None:
    root = library_root.resolve()
    if not (root / "index.json").is_file():
        raise PublishError(f"--apply 指向的目录里没有 index.json：{root}")
    info = run_check(src, library_root=root)
    author, key, version = info["author"], info["key"], info["version"]

    target = root / "plugins" / author / key / version
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src, target, ignore=shutil.ignore_patterns(*SKIP_COPY_NAMES))
    print(f"  ✓ 已冻结到 {target.relative_to(root).as_posix()}/")

    merge_version(
        root,
        author=author,
        key=key,
        version=version,
        path=target.relative_to(root).as_posix(),
    )
    print("  ✓ 已合并进 index.json")

    if clean_source:
        shutil.rmtree(src)
        # 顺手收掉空掉的作者目录，别在 sources/ 里留一串空壳
        parent = src.parent
        while parent.name != "sources" and parent.is_dir() and not any(parent.iterdir()):
            parent.rmdir()
            parent = parent.parent
        print(f"  ✓ 已清理暂存源码 {src}")

    print("== 发布完成：提交并推送，Loom 侧会在下一个同步周期拉到 ==")


def run_start(spec: str, *, library_root: Path) -> None:
    root = library_root.resolve()
    parts = [piece for piece in spec.replace("\\", "/").split("/") if piece]
    if len(parts) != 2:
        raise PublishError("用法：start <作者>/<插件键>")
    author, key = parts
    published = root / "plugins" / author / key
    if not published.is_dir():
        raise PublishError(f"没有这个插件的任何已发布版本：{published}")
    versions = sorted(
        (path for path in published.iterdir() if path.is_dir()),
        key=lambda path: version_sort_key(path.name),
    )
    if not versions:
        raise PublishError(f"{published} 下没有任何版本目录")
    latest = versions[-1]
    target = root / "sources" / author / key
    if target.exists():
        raise PublishError(f"sources 里已经有这个插件了，先处理掉再 start：{target}")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(latest, target, ignore=shutil.ignore_patterns(*SKIP_COPY_NAMES))
    print(f"  ✓ 已把 v{latest.name} 复制到 {target.relative_to(root).as_posix()}/")
    print(f"  → 改完记得把 plugin.toml 的 version 从 {latest.name} 往上加，再提交 PR")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(
        prog="loom_publish.py",
        description="Loom 插件库发布脚本：check（自检）/ build（冻结并更新索引）/ start（取回最新版本继续改）",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_check = sub.add_parser("check", help="贡献者自检")
    p_check.add_argument("src", metavar="<源码目录>")
    p_check.add_argument("--library", metavar="<库根目录>", help="显式指定库根目录（默认从路径推断）")

    p_build = sub.add_parser("build", help="冻结版本目录并合并进 index.json")
    p_build.add_argument("src", metavar="<源码目录>")
    p_build.add_argument("--apply", metavar="<库根目录>", required=True)
    p_build.add_argument("--clean-source", action="store_true", help="发布成功后删掉暂存源码")

    p_start = sub.add_parser("start", help="把最新已发布版本复制回 sources/ 继续改")
    p_start.add_argument("spec", metavar="<作者>/<插件键>")
    p_start.add_argument("--library", metavar="<库根目录>", default=".")

    args = parser.parse_args()
    try:
        if args.command == "check":
            run_check(
                Path(args.src),
                library_root=Path(args.library) if args.library else None,
            )
        elif args.command == "build":
            run_build(Path(args.src), library_root=Path(args.apply), clean_source=args.clean_source)
        else:
            run_start(args.spec, library_root=Path(args.library))
    except PublishError as exc:
        print(f"\n✗ {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
