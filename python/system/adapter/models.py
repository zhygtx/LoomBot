from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

STATES = {"DISABLED", "PENDING", "STARTING", "CONNECTING", "LISTENING", "ONLINE", "RETRY_WAIT", "DEGRADED", "STOPPING", "FAILED"}


@dataclass(slots=True)
class DesiredConnection:
    connection_id: int
    revision: int
    enabled: bool
    plugin_version_id: int
    plugin_key: str
    plugin_version: str
    plugin_path: str
    entry_point: str
    connection_type: str
    direction: str
    config: dict[str, Any] = field(default_factory=dict)
    endpoint_path: str | None = None
    config_hash: str | None = None

    @classmethod
    def from_dict(cls, body: dict[str, Any]) -> "DesiredConnection":
        adapter = body.get("adapter") or {}
        transport = body.get("transport") or {}
        return cls(
            connection_id=int(body["connectionId"]), revision=int(body.get("revision", body.get("desiredRevision", 0))),
            enabled=bool(body.get("enabled", False)), plugin_version_id=int(body.get("pluginVersionId", 0)),
            plugin_key=str(adapter.get("package", body.get("pluginKey", ""))), plugin_version=str(adapter.get("version", body.get("pluginVersion", ""))),
            plugin_path=str(body.get("pluginPath", "")), entry_point=str(body.get("entryPoint", "main.py")),
            connection_type=str(adapter.get("type", body.get("connectionType", ""))),
            direction=str(transport.get("direction", body.get("direction", "FORWARD"))).upper(), config=dict(body.get("config") or {}),
            endpoint_path=transport.get("endpointId", body.get("endpointPath")), config_hash=body.get("configHash"))

    def as_dict(self) -> dict[str, Any]:
        return {"connectionId": self.connection_id, "revision": self.revision, "enabled": self.enabled,
                "adapter": {"package": self.plugin_key, "version": self.plugin_version, "type": self.connection_type},
                "transport": {"direction": self.direction, "endpointId": self.endpoint_path}, "config": self.config, "configHash": self.config_hash}


@dataclass(slots=True)
class ConnectionObservation:
    connection_id: int
    revision: int = 0
    state: str = "PENDING"
    error_code: str | None = None
    error_message: str | None = None
    last_received_at: int = 0
    last_sent_at: int = 0
    reconnect_count: int = 0
    worker_pid: int | None = None
    observed_at: int = 0

    def as_dict(self) -> dict[str, Any]:
        return {"connectionId": self.connection_id, "observedRevision": self.revision, "state": self.state,
                "errorCode": self.error_code, "errorMessage": self.error_message, "lastReceivedAt": self.last_received_at,
                "lastSentAt": self.last_sent_at, "reconnectCount": self.reconnect_count, "workerPid": self.worker_pid, "observedAt": self.observed_at}
