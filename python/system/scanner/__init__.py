"""插件目录扫描：把插件包解析成节点目录。

适配器插件和工作流节点插件共用同一份扫描实现，产出同一份目录结构，
由 `python -m system.scanner.describe <插件目录>` 输出给 Java 插件同步器。
"""

from system.scanner.catalog import build_catalog
from system.scanner.errors import ScanError

__all__ = ["build_catalog", "ScanError"]
