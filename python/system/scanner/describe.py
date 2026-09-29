"""插件目录扫描入口。

用法：

    python -m system.scanner.describe <插件目录>

标准输出是整份目录 JSON（适配器、工作流节点、实体）；扫描失败时向标准错误写原因并返回非零退出码。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from system.scanner import ScanError, build_catalog


def main() -> None:
    parser = argparse.ArgumentParser(description="扫描插件包并输出节点目录")
    parser.add_argument("plugin_dir", help="插件包目录")
    args = parser.parse_args()
    try:
        catalog = build_catalog(Path(args.plugin_dir))
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
