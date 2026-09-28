from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class HostConfig:
    host: str
    port: int
    ws_host: str
    ws_port: int
    control_token: str
    plugin_root: Path
    redis_url: str
    task_stream: str
    index_prefix: str
    task_ttl_seconds: int
    python_command: str

    @classmethod
    def from_env(cls) -> "HostConfig":
        return cls(
            host=os.getenv("ADAPTER_HOST", "127.0.0.1"), port=int(os.getenv("ADAPTER_PORT", "9100")),
            ws_host=os.getenv("ADAPTER_WS_HOST", "127.0.0.1"), ws_port=int(os.getenv("ADAPTER_WS_PORT", "9000")),
            control_token=os.getenv("ADAPTER_CONTROL_TOKEN", "loom-dev-adapter-token"),
            plugin_root=Path(os.getenv("ADAPTER_PLUGIN_ROOT", "plugins")).resolve(),
            redis_url=os.getenv("ADAPTER_REDIS_URL", "redis://localhost:6379/0"),
            task_stream=os.getenv("WORKFLOW_TASK_STREAM", "workflow:task:v1"),
            index_prefix=os.getenv("WORKFLOW_INDEX_PREFIX", "loom:workflow:index"),
            task_ttl_seconds=int(os.getenv("WORKFLOW_TASK_TTL_SECONDS", "300")),
            python_command=os.getenv("ADAPTER_PYTHON_COMMAND", "python"),
        )
