"""子扫描器契约。

一个插件库要接进 Loom，最多提供三个函数——前两个管"目录期"，第三个管"运行期"：

    def list_plugins(repo_root: Path) -> list[dict]:
        '''列出这个库有哪些插件、每个插件有哪些版本。'''

    def scan_plugin(plugin_dir: Path, entry: dict) -> dict:
        '''把一个插件目录翻译成 Loom 的节点目录 JSON。'''

    def load_nodes(plugin_dir: Path) -> dict[str, Callable]:
        '''导入插件代码，把节点键映射到可调用的函数。'''

前两个是必需的，第三个只在"这个库的插件要被执行"时才需要——纯展示型的库可以不实现，
运行时发现缺失会明确报"这个库不支持执行"，而不是抛一个看不懂的导入错误。

主扫描器（`system.scanner.describe`）只做三件事：按名字找到子扫描器、把它的结果收进统一结构、
保证输出永远是 Loom 认的形状。它自己不做任何自定义解析——那是子扫描器的事。

**为什么运行期也要走子扫描器**：不同格式的插件布局和导入方式不一样。Loom 的插件是
`nodes/*.py` + `@node` 装饰器；GeneralBot 的插件是包内相对导入（`from .renderer import ...`）。
只有子扫描器知道自己的库该怎么导入，所以"怎么找节点"和"怎么调用节点"必须由同一份代码给出。

**子扫描器跟着插件库走，不在 Loom 的源码里。** 异构库把 `scanner.py` 放进自己的库文件夹，
`repo.json` 里写 `"scanner": "local"`；这样接一个新库不用改 Loom 的代码，删掉文件夹就干净退出。

唯一的内置项是 `loom` —— Loom 自己的格式（`index.json` + `plugin.toml` + 装饰器）。它是框架的一部分：
扫描规则和 Loom 的装饰器定义必须同步演进，放进数据目录反而会漂。所以 Loom 自己的库不写 `scanner.py`，
直接走这个内置项。
"""

from __future__ import annotations

import importlib
import importlib.util
import sys
from pathlib import Path
from types import ModuleType

from system.scanner.errors import ScanError

BUILTIN_SCANNERS = {
    "loom": "system.scanner.subscanners.loom",
}

LOCAL_SCANNER = "local"
LOCAL_SCANNER_FILE = "scanner.py"


def load_sub_scanner(
    name: str,
    repo_root: Path,
    required: tuple[str, ...] = ("list_plugins", "scan_plugin"),
) -> ModuleType:
    """按名字加载子扫描器，并校验它实现了契约。

    名字是内置键（目前只有 `loom`）时用 Loom 自带的那份；`local` 时加载插件库文件夹里的
    `scanner.py`。加载失败、或者缺函数，都在这里报错——不要等到主扫描器拼装结果时才发现。

    `required` 是要校验的函数名：目录期用默认的两个，运行期只要求 `load_nodes`。
    """
    scanner_name = (name or "loom").strip()
    if scanner_name == LOCAL_SCANNER:
        module = _load_local_scanner(repo_root)
    else:
        module_path = BUILTIN_SCANNERS.get(scanner_name)
        if module_path is None:
            known = "、".join(sorted([*BUILTIN_SCANNERS, LOCAL_SCANNER]))
            raise ScanError(f"未知的子扫描器：{scanner_name}（可用：{known}）")
        try:
            module = importlib.import_module(module_path)
        except ImportError as exc:
            raise ScanError(f"加载子扫描器失败：{scanner_name}，{exc}") from exc
    for function_name in required:
        if not callable(getattr(module, function_name, None)):
            raise ScanError(
                f"子扫描器 {scanner_name} 没有实现 {function_name}()"
            )
    return module


def _load_local_scanner(repo_root: Path) -> ModuleType:
    """加载插件库文件夹里的 scanner.py。

    按文件路径加载而不是 import：这个文件不在 `system.scanner` 包里，也不该被装进 Python 路径
    （那样两个库的同名 scanner.py 会互相覆盖）。
    """
    path = repo_root / LOCAL_SCANNER_FILE
    if not path.is_file():
        raise ScanError(f"插件库文件夹里没有 {LOCAL_SCANNER_FILE}：{repo_root}")
    module_name = f"loom_plugin_repo_scanner_{abs(hash(str(path.resolve())))}"
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise ScanError(f"无法加载子扫描器：{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except Exception as exc:  # noqa: BLE001 - 子扫描器是插件库自己的代码，报清楚就行
        sys.modules.pop(module_name, None)
        raise ScanError(f"子扫描器执行失败：{path}，{exc}") from exc
    return module
