"""工作流运行时配置。"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class WorkerConfig:
    redis_url: str
    task_stream: str
    log_stream: str
    consumer_group: str
    consumer_name: str
    max_concurrency: int
    execution_timeout_seconds: float
    java_base_url: str
    workflow_token: str
    adapter_base_url: str
    adapter_token: str
    test_host: str
    test_port: int
    artifact_dir: Path

    @classmethod
    def from_env(cls) -> "WorkerConfig":
        return cls(
            redis_url=os.getenv("ADAPTER_REDIS_URL", "redis://localhost:6379/0"),
            task_stream=os.getenv("WORKFLOW_TASK_STREAM", "workflow:task:v1"),
            log_stream=os.getenv("WORKFLOW_LOG_STREAM", "workflow:execution-log:v1"),
            consumer_group=os.getenv("WORKFLOW_CONSUMER_GROUP", "workflow-worker"),
            consumer_name=os.getenv(
                "WORKFLOW_CONSUMER_NAME", f"worker-{os.getpid()}"
            ),
            max_concurrency=_int("WORKFLOW_MAX_CONCURRENCY", 16),
            execution_timeout_seconds=float(
                os.getenv("WORKFLOW_TIMEOUT_SECONDS", "60")
            ),
            java_base_url=os.getenv("WORKFLOW_JAVA_BASE_URL", "http://127.0.0.1:8080").rstrip("/"),
            workflow_token=os.getenv("WORKFLOW_CONTROL_TOKEN", "loom-dev-workflow-token"),
            adapter_base_url=os.getenv(
                "ADAPTER_CONTROL_BASE_URL", "http://127.0.0.1:9100"
            ).rstrip("/"),
            adapter_token=os.getenv("ADAPTER_CONTROL_TOKEN", "loom-dev-adapter-token"),
            test_host=os.getenv("WORKFLOW_TEST_HOST", "127.0.0.1"),
            test_port=_int("WORKFLOW_TEST_PORT", 9200),
            # 大内容（二进制/文件）落盘目录；Java 读同一个目录提供下载，所以由 Java 通过环境变量下发
            artifact_dir=Path(
                os.getenv("WORKFLOW_ARTIFACT_DIR", "artifacts")
            ).resolve(),
        )
