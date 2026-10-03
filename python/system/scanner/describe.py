"""插件库扫描入口（主扫描器）。

用法：

    python -m system.scanner.describe <插件库文件夹> [--scanner loombot|local]

`<插件库文件夹>` 形如 `python/plugins/<库 key>/`，里面：

```text
<库文件夹>/
├── repo/           git 工作副本（纯本地库就是一个普通目录），插件源在这里
├── repo.json       库的声明（key / url / branch / scanner），Java 侧读它
└── scanner.py      可选：这个库自己的子扫描器，--scanner local 时用它
```

**主扫描器不解析任何库格式。** 它只做三件事：按名字找到子扫描器、把结果收进统一结构、
保证输出形状永远是 LoomBot 认的。自定义解析全在子扫描器里（契约见 `system/scanner/contract.py`）。

输出（stdout，UTF-8）：

```json
{
  "protocolVersion": 1,
  "plugins": [
    { "key": "text-tools", "namespace": "loombot",
      "versions": [
        { "version": "1.0.0", "path": "text-tools", "publishedTime": "...",
          "catalog": { "...节点目录..." } }
      ] }
  ]
}
```

一次扫完整个库而不是一个插件起一次进程：一个有几十个插件的库，原来要起几十次 Python，
每次约 100ms，全量重扫时这个开销比解析本身还大。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from system.scanner.contract import load_sub_scanner
from system.scanner.errors import ScanError

PROTOCOL_VERSION = 1

#: 插件库文件夹里放 git 工作副本的子目录名。LoomBot 只往这里写（clone / pull），
#: 不碰库文件夹里的其它东西——子扫描器就放在工作副本外面，才不会被 git 覆盖。
WORKING_COPY_DIR = "repo"


def build_repo_catalog(repo_root: Path, scanner_name: str) -> dict[str, Any]:
    """扫描一个插件库文件夹，产出全部插件的目录。"""
    root = Path(repo_root).resolve()
    if not root.is_dir():
        raise ScanError(f"插件库文件夹不存在：{root}")
    work = (root / WORKING_COPY_DIR).resolve()
    if not work.is_dir():
        raise ScanError(f"插件库缺少工作副本目录 {WORKING_COPY_DIR}/：{root}")

    scanner = load_sub_scanner(scanner_name, root)
    try:
        listed = scanner.list_plugins(work)
    except ScanError:
        raise
    except Exception as exc:  # noqa: BLE001 - 子扫描器是外部代码，转成可读错误
        raise ScanError(f"子扫描器 list_plugins() 失败：{exc}") from exc
    if not isinstance(listed, list):
        raise ScanError("子扫描器 list_plugins() 必须返回列表")

    plugins: list[dict[str, Any]] = []
    for entry in listed:
        if not isinstance(entry, dict):
            raise ScanError("子扫描器 list_plugins() 的元素必须是对象")
        key = str(entry.get("key") or "").strip()
        if not key:
            raise ScanError("子扫描器返回了缺少 key 的插件")
        versions: list[dict[str, Any]] = []
        for version in entry.get("versions") or []:
            if not isinstance(version, dict):
                continue
            relative = str(version.get("path") or "").strip()
            if not relative:
                continue
            plugin_dir = (work / relative).resolve()
            # 子扫描器是外部代码：它给的路径必须落在工作副本里，别让它指到仓库外面去
            if not plugin_dir.is_relative_to(work):
                raise ScanError(f"插件路径越出工作副本：{relative}")
            if not plugin_dir.is_dir():
                raise ScanError(f"插件目录不存在：{relative}")
            try:
                catalog = scanner.scan_plugin(plugin_dir, entry)
            except ScanError:
                raise
            except Exception as exc:  # noqa: BLE001
                raise ScanError(f"子扫描器 scan_plugin() 失败：{relative}，{exc}") from exc
            versions.append(
                {
                    "version": str(version.get("version") or "").strip(),
                    "path": relative,
                    "publishedTime": str(version.get("publishedTime") or "").strip(),
                    "catalog": catalog,
                }
            )
        if not versions:
            continue
        plugins.append(
            {
                "key": key,
                "namespace": str(entry.get("namespace") or "").strip(),
                "versions": versions,
            }
        )
    return {"protocolVersion": PROTOCOL_VERSION, "plugins": plugins}


def main() -> None:
    parser = argparse.ArgumentParser(description="扫描插件库并输出统一的目录 JSON")
    parser.add_argument("repo_root", help="插件库文件夹（含 repo/ 与 repo.json）")
    parser.add_argument(
        "--scanner",
        default="loombot",
        help="子扫描器：内置的 loombot，或 local（用库文件夹里的 scanner.py）",
    )
    args = parser.parse_args()
    try:
        catalog = build_repo_catalog(Path(args.repo_root), args.scanner)
    except ScanError as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(2) from exc
    # 直接写 UTF-8 字节：Windows 上 sys.stdout 默认按本地代码页编码，
    # Java 侧按 UTF-8 读取会拿到乱码或非法字节。
    payload = json.dumps(catalog, ensure_ascii=False, indent=2).encode("utf-8")
    sys.stdout.buffer.write(payload)
    sys.stdout.buffer.write(b"\n")
    sys.stdout.buffer.flush()


if __name__ == "__main__":
    main()
