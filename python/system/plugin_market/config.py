"""插件库机器人的配置。

分两处，界线是**密钥**：

- **非密钥**（仓库坐标、分支、gate 开关、AI 地址与模型）：`--config <json>`，或环境变量
  `LOOMBOT_PLUGIN_MARKET_CONFIG`（Java 的定时任务把 `sys_config` 里的值序列化后传进来）。
  这些值后台可改，改完下一轮生效。
- **密钥**（Gitee token、AI key）：**只从本地文件读**，默认 `python/secrets/plugin-market.json`。
  不放数据库、不走接口——`sys_config` 的读接口是原样返回值的，放进去等于把一把有 `projects`
  权限的 token 摊给所有能看系统配置的人。
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

PY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SECRETS_FILE = PY_ROOT / "secrets" / "plugin-market.json"
ENV_SETTINGS = "LOOMBOT_PLUGIN_MARKET_CONFIG"
ENV_SECRETS = "LOOMBOT_PLUGIN_MARKET_SECRETS"

# 默认值刻意保守：默认关闭、默认只审不合不发
DEFAULTS: dict[str, Any] = {
    "enabled": False,
    "repo": {"url": "", "branch": "main"},
    "ai": {
        "enabled": True,
        "base_url": "https://api.deepseek.com",
        "model": "deepseek-flash",
        "json_mode": True,
        "timeout": 120,
    },
    "gates": {
        "auto_merge": True,
        "auto_publish": True,
        "approve_before_merge": True,
        "merge_method": "squash",
    },
    "poll_interval_seconds": 300,
    "log_days": 30,
    "work_dir": "_work",
}


class ConfigError(Exception):
    """配置缺失或格式不对。"""


def load_settings(path: Path | None = None) -> dict[str, Any]:
    """读非密钥配置；`--config` 优先，其次环境变量，最后全用默认值。"""
    raw: Any = None
    if path is not None:
        raw = _read_json(path, "配置文件")
    elif os.environ.get(ENV_SETTINGS):
        try:
            raw = json.loads(os.environ[ENV_SETTINGS])
        except ValueError as exc:
            raise ConfigError(f"环境变量 {ENV_SETTINGS} 不是合法 JSON：{exc}") from exc
    return _merge(DEFAULTS, raw or {})


def load_secrets(path: Path | None = None) -> dict[str, Any]:
    """读本地密钥文件。缺文件不算错——AI 会退回启发式补全、Gitee 那步会明确报错。"""
    target = path or Path(os.environ.get(ENV_SECRETS) or DEFAULT_SECRETS_FILE)
    if not target.is_file():
        return {}
    data = _read_json(target, "密钥文件")
    if not isinstance(data, dict):
        raise ConfigError(f"密钥文件必须是 JSON 对象：{target}")
    return data


def secrets_path(path: Path | None = None) -> Path:
    """密钥文件该在哪——报错时把路径写出来，省得用户到处找。"""
    return path or Path(os.environ.get(ENV_SECRETS) or DEFAULT_SECRETS_FILE)


def _read_json(path: Path, what: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ConfigError(f"{what}不存在：{path}") from exc
    except (OSError, ValueError) as exc:
        raise ConfigError(f"{what}读取失败（{path}）：{exc}") from exc


def _merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    """一层深合并：嵌套的 dict 合并，其余直接覆盖。"""
    result = dict(base)
    for key, value in (override or {}).items():
        current = result.get(key)
        if isinstance(current, dict) and isinstance(value, dict):
            result[key] = {**current, **value}
        else:
            result[key] = value
    return result
