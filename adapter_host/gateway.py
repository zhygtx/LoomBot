from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import WebSocket, WebSocketDisconnect

log = logging.getLogger("adapter-gateway")


class WebSocketGateway:
    def __init__(self, supervisor: Any) -> None:
        self.supervisor = supervisor

    async def handle(self, websocket: WebSocket, connection_id: int) -> None:
        await websocket.accept()
        log.info("平台 WebSocket 已接入: connection=%s", connection_id)
        session_id = None
        try:
            session_id, outgoing = await self.supervisor.open_session(connection_id, {"query": dict(websocket.query_params), "headers": dict(websocket.headers)})
            sender = asyncio.create_task(self._send_loop(websocket, outgoing))
            try:
                while True:
                    message = await websocket.receive()
                    if message.get("type") == "websocket.disconnect":
                        break
                    if message.get("text") is not None:
                        await self.supervisor.feed_session(session_id, message["text"])
                    elif message.get("bytes") is not None:
                        await self.supervisor.feed_session(session_id, message["bytes"])
            finally:
                sender.cancel()
                await self.supervisor.close_session(session_id)
        except WebSocketDisconnect:
            if session_id:
                await self.supervisor.close_session(session_id)
        except Exception as exc:
            log.warning("平台 WebSocket 处理失败: connection=%s error=%s", connection_id, exc)
            if websocket.client_state.name != "DISCONNECTED":
                await websocket.close(code=1011, reason=str(exc)[:120])

    @staticmethod
    async def _send_loop(websocket: WebSocket, outgoing: asyncio.Queue[Any]) -> None:
        while True:
            frame = await outgoing.get()
            if frame is None:
                # 插件主动关闭逻辑会话时，底层 WebSocket 也必须关闭，
                # 否则平台侧会一直以为连接还在，而运行时状态已经回到 LISTENING。
                try:
                    await websocket.close()
                except Exception:
                    pass
                return
            if isinstance(frame, bytes):
                await websocket.send_bytes(frame)
            else:
                await websocket.send_text(str(frame))
