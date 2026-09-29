"""插件扫描错误。"""

from __future__ import annotations


class ScanError(Exception):
    """插件包不符合契约时抛出，扫描入口据此让同步失败。"""
