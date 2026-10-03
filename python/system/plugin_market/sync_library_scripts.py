#!/usr/bin/env python3
"""把贡献者要用的脚本同步到插件库仓库。

插件库仓库里保留 `scripts/loom_publish.py` 和 `scripts/audit.py`，是为了让贡献者
clone 下来就能本地自检（`check` / 安全扫描），不用装 Loom。

但**权威实现只有一份，在主项目这里**——库仓库里那两份是生成物，文件头会写明来源。
改逻辑改这边，然后跑一次同步；两边各改各的必然漂移。

用法（在 `python/` 目录下）：

    python -m system.plugin_market.sync_library_scripts <插件库工作副本>
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
# 生成物：库仓库里要有的、给贡献者用的脚本
GENERATED = ("loom_publish.py", "audit.py")


def render(source: Path) -> str:
    """给源码加上「生成物」头，别让人在库仓库里直接改。"""
    text = source.read_text(encoding="utf-8")
    banner = (
        "# 本文件由 Loom 主项目生成，请勿直接修改。\n"
        f"# 来源：system/plugin_market/{source.name}\n"
        "# 同步：python -m system.plugin_market.sync_library_scripts <插件库工作副本>\n"
    )
    if text.startswith("#!"):
        first, _, rest = text.partition("\n")
        return f"{first}\n{banner}{rest}"
    return banner + text


def sync(repo: Path) -> list[str]:
    scripts = repo / "scripts"
    scripts.mkdir(parents=True, exist_ok=True)
    written: list[str] = []
    for name in GENERATED:
        source = HERE / name
        if not source.is_file():
            raise SystemExit(f"✗ 主项目里找不到 {source}")
        target = scripts / name
        content = render(source)
        if not target.is_file() or target.read_text(encoding="utf-8") != content:
            target.write_text(content, encoding="utf-8")
            written.append(f"scripts/{name}")
    return written


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="把贡献者脚本同步进插件库仓库")
    parser.add_argument("repo", metavar="<插件库工作副本>")
    args = parser.parse_args()

    repo = Path(args.repo).resolve()
    if not (repo / "index.json").is_file():
        print(f"✗ 这不像一个插件库工作副本（没有 index.json）：{repo}", file=sys.stderr)
        return 1
    written = sync(repo)
    if written:
        print("已同步：" + "、".join(written))
    else:
        print("已经是最新的，无需同步")
    return 0


if __name__ == "__main__":
    sys.exit(main())
