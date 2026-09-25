#!/usr/bin/env python3
"""Loom 假协议适配器 —— 端到端集成测试专用。

Loom 的分层是「Java 管连接、Python 管协议」。本脚本扮演协议侧，但一个真实协议都不实现：只为让
Java 侧连接管理器（状态机 / 重连 / 会话替换）能在「真子进程 + 真管道」下端到端验证。Java 用
ProcessBuilder 起 `python main.py`，注入 PYTHONUNBUFFERED=1 与 FAKE_ADAPTER_CONFIG，然后写子进程
stdin、读其 stdout；两侧除这两根管道外没有共享状态。

三条铁律：① stdout 只跑 NDJSON，调试信息一律 stderr；② 每条消息写完立刻 flush（Java 逐行读，
攒批 = 消息不到）；③ stdin 读到 EOF 立刻退出（JVM 被强杀时管道关闭，这是唯一的孤儿防护）。

以 Java 实现为准（com.loom.connection.adapter.AdapterSession）：只有 type="reply" 参与配对（无
call.reply）；ws.opened / ws.closed / conn.reload 是单向通知（不带 id），绝不能回任何东西；ws.send
读扁平字段 handleId/encoding/content；反向连接的 handleId 由 Java 经 ws.opened 下发，不该猜它的格式。
"""

import base64
import binascii
import collections
import json
import os
import sys
import time
import traceback

# stdout 纪律：先抓住真正的 stdout 作为唯一协议出口，再把 sys.stdout 指向 stderr —— 这样任何一句
# 无心的 print() 都只落进 stderr 日志，而不会插进协议流。污染协议流会让 Java 侧
# IpcChannel.protocolViolations 飙涨、消息「莫名其妙丢失」，事后极难定位。
_PROTOCOL_OUT = sys.stdout
sys.stdout = sys.stderr

PROTOCOL_VERSION = 1
CAPABILITIES = ["ws_parser:fake"]
MATCHED_EVENT_TYPE = "wanted"  # 会命中触发条件的 type，写死以免测试有歧义
HANDLE_PREFIX = "h-"  # Java 的 handleId 形如 h-<connectionId>-<序号>，这里只在兜底时才用
DEFAULTS = {"connectionType": "fake", "direction": "REVERSE", "displayName": "假适配器（E2E 测试用）",
            "echoFrames": False, "frameDelayMs": 0}

def log(message: str) -> None:
    """调试日志唯一出口：stderr，立即 flush（Java 会持续排空 stderr）。"""
    print(f"[fake-adapter] {message}", file=sys.stderr, flush=True)

def send(msg_type, payload=None, mid=None) -> None:
    """写一条协议消息。flush 是硬要求（铁律②）：管道下 stdout 块缓冲，不 flush 就是消息不到。"""
    envelope = {"type": msg_type}
    if mid is not None:
        envelope["id"] = mid
    if payload is not None:
        envelope["payload"] = payload
    _PROTOCOL_OUT.write(json.dumps(envelope, ensure_ascii=False, separators=(",", ":")) + "\n")
    _PROTOCOL_OUT.flush()

def _text(value):
    """宽松取非空字符串：类型不对就当「没给」，配置写错不该让进程起不来。"""
    return value if isinstance(value, str) and value.strip() else None

def load_config() -> dict:
    """读 FAKE_ADAPTER_CONFIG（JSON 字符串）；坏配置一律回退默认值并留日志。"""
    config, raw = dict(DEFAULTS), os.environ.get("FAKE_ADAPTER_CONFIG", "").strip()
    try:
        parsed = json.loads(raw) if raw else None
    except ValueError as exc:
        parsed = None
        log(f"FAKE_ADAPTER_CONFIG 不是合法 JSON（{exc}），回退默认配置")
    if not isinstance(parsed, dict):
        log("没有可用的 FAKE_ADAPTER_CONFIG 对象，使用默认配置"); return config
    config["connectionType"] = _text(parsed.get("connectionType")) or config["connectionType"]
    config["displayName"] = _text(parsed.get("displayName")) or config["displayName"]
    if isinstance(parsed.get("echoFrames"), bool):  # 类型不符就保留默认值，不猜
        config["echoFrames"] = parsed["echoFrames"]
    delay = parsed.get("frameDelayMs")  # 负延迟会让 time.sleep 抛错，先夹住
    if type(delay) is int:
        config["frameDelayMs"] = max(0, delay)
    direction = (_text(parsed.get("direction")) or config["direction"]).upper()
    if direction not in ("REVERSE", "FORWARD"):  # 拼错的 direction 需要日志，而不是崩掉
        log(f"direction={direction!r} 非法，回退 REVERSE")
        direction = "REVERSE"
    config["direction"] = direction
    log(f"已加载配置：{config}")
    return config

class _Exit(Exception):
    """要么 stdin EOF（JVM 已死）、要么收到 shutdown：都该立刻结束本进程。"""

def _payload(msg: dict) -> dict:
    payload = msg.get("payload")
    return payload if isinstance(payload, dict) else {}

def _extract_handle_id(reply):
    """从 ws.open 的回复里挖 handleId：Java 放在 payload.handleId，另两种是历史兼容。"""
    for source in (reply, reply.get("result") if isinstance(reply, dict) else None):
        for key in ("handleId", "handle"):
            if isinstance(source, dict) and _text(source.get(key)):
                return source[key]
    return None

def _decode_frame(data, handle_id):
    """把 ws.frame 的 data 还原成文本；还原不出来返回 None（调用方丢弃）。"""
    if not isinstance(data, dict) or not isinstance(data.get("content"), str):
        log(f"handleId={handle_id} 的 ws.frame 不是 {{encoding, content}} 形状，丢弃"); return None
    content, encoding = data["content"], _text(data.get("encoding")) or "text"
    if encoding == "base64":
        try:
            return base64.b64decode(content, validate=False).decode("utf-8")
        except (binascii.Error, UnicodeDecodeError, ValueError) as exc:
            log(f"handleId={handle_id} 的 base64 帧解不出 UTF-8 文本（{exc}），丢弃"); return None
    if encoding != "text":
        log(f"handleId={handle_id} 的 encoding={encoding} 未知，按 text 处理")
    return content

class FakeAdapter:
    def __init__(self, config: dict) -> None:
        self.connection_type, self.direction = config["connectionType"], config["direction"]
        self.display_name = config["displayName"]
        self.echo_frames, self.frame_delay_ms = config["echoFrames"], config["frameDelayMs"]
        self.connections = {}  # connectionId -> {"handleId": 最新句柄, "config": ...}
        self.handles = {}  # handleId -> connectionId（收帧靠它反查，正/反向都写这张表）
        self.deferred = collections.deque()  # 等待回复期间暂存的消息，稍后按原顺序处理
        self._req_seq = 0
    def send_hello(self) -> None:
        """启动后立即发、只发一次；Java 靠它知道适配器是谁、配置长什么样。"""
        send("hello", {
            "protocolVersion": PROTOCOL_VERSION, "connectionType": self.connection_type,
            "direction": self.direction, "displayName": self.display_name,
            "capabilities": list(CAPABILITIES),
            "configSchema": {
                # x-handshake 只有 REVERSE 才有意义，但写上无妨：Java 按 direction 决定读不读
                "type": "object", "required": ["token"], "x-direction": self.direction,
                "x-handshake": {"mode": "queryParam", "paramName": "access_token", "secretField": "token"},
                "properties": {
                    "token": {"type": "string", "title": "鉴权 Token", "x-secret": True,
                              "x-generate": "random32", "x-order": 1},
                    "targetUrl": {"type": "string", "title": "正向连接目标地址", "x-order": 2},
                },
            },
        })
        log(f"已发送 hello（connectionType={self.connection_type}, direction={self.direction}）")
    def _read_message(self):
        """读一行并解析。stdin 结束抛 _Exit；坏行记日志后返回 None（一行坏数据不该弄死进程）。"""
        line = sys.stdin.readline()
        if line == "":  # 铁律③：EOF 即 JVM 已退出
            raise _Exit("stdin 已 EOF —— 判定 JVM 已退出，本进程立即结束（孤儿防护）")
        line = line.strip()
        if not line:
            return None  # 空行忽略：Java 侧也不会发，但不能因此崩掉
        try:
            msg = json.loads(line)
        except ValueError as exc:
            log(f"stdin 收到非法 JSON（{exc}），已丢弃：{line[:200]}"); return None
        if not isinstance(msg, dict):
            log(f"stdin 收到非对象 JSON，已丢弃：{line[:200]}"); return None
        return msg
    def _next_message(self):
        """主循环取消息：先清空等待回复期间暂存的，保证「顺序处理」语义。"""
        return self.deferred.popleft() if self.deferred else self._read_message()
    def _wait_reply(self, req_id):
        """等指定 id 的回复。等待期间照旧消费管道，shutdown / EOF 依然能立刻结束等待。"""
        while True:
            msg = self._read_message()
            if msg is None:
                continue
            if msg.get("id") == req_id:  # id 是配对的唯一依据
                return _payload(msg)
            if msg.get("type") == "shutdown":
                raise _Exit("等待回复期间收到 shutdown，正常退出")  # 否则会被 Java 强杀
            self.deferred.append(msg)  # 不是我要的回复：暂存，绝不在这里重入分发
    def _request(self, msg_type, payload):
        """发带 id 的请求并同步等回复；Java 回失败就返回 None，由调用方降级。"""
        self._req_seq += 1
        req_id = f"py-{self._req_seq}"
        send(msg_type, payload, mid=req_id)
        reply = self._wait_reply(req_id)
        if reply is None or reply.get("ok") is False:
            log(f"{msg_type}(id={req_id}) Java 回复失败：{reply!r}"); return None
        return reply
    def _reply(self, msg, ok, **fields) -> None:
        if msg.get("id") is not None:  # 没带 id = 对方不需要回复，多发只会污染协议流
            send("reply", {"ok": ok, **fields}, mid=msg["id"])
    def _bind_handle(self, cid, handle_id, config=None) -> None:
        """登记 handleId ↔ connectionId（正向 ws.open 与反向 ws.opened 共用）；同句柄直接覆盖。"""
        entry = self.connections.get(cid) or {"handleId": handle_id, "config": {}}
        entry["handleId"] = handle_id
        if config is not None:
            entry["config"] = config
        self.connections[cid] = entry
        self.handles[handle_id] = cid
    def _forget_handles(self, cid) -> int:
        """清掉某连接名下**所有**句柄：平台断开重连后 Java 会换新句柄，旧的不该留在表里。"""
        stale = [h for h, owner in self.handles.items() if owner == cid]
        for handle_id in stale:
            del self.handles[handle_id]
        return len(stale)
    def _resolve_connection(self, handle_id, payload):
        """映射表 → payload.connectionId → h- 前缀兜底；后两级只认「仍登记着」的连接 ——
        conn.close 之后旧句柄的帧必须丢弃，否则会在 Java 侧造出幽灵工作流。
        """
        cid = self.handles.get(handle_id)
        if cid:
            return cid
        cid = _text(payload.get("connectionId"))
        if cid and cid in self.connections:
            return cid
        # 最后才猜：handleId 的格式是 Java 的实现细节，猜它很脆，而且只认仍登记着的连接
        guess = handle_id[len(HANDLE_PREFIX):].split("-")[0] \
            if handle_id and handle_id.startswith(HANDLE_PREFIX) else None
        return guess if guess in self.connections else None
    def run(self) -> None:
        while True:
            msg = self._next_message()
            if msg is not None:
                self._dispatch(msg)
    def _dispatch(self, msg: dict) -> None:
        msg_type = msg.get("type")
        if not isinstance(msg_type, str) or not msg_type:
            log(f"消息缺 type，已丢弃：{json.dumps(msg, ensure_ascii=False)[:200]}"); return
        handler = getattr(self, "_on_" + msg_type.replace(".", "_"), None)
        if handler is None:  # 带 id 的请求绝不悬空：明确回失败，比让 Java 等到超时好排查
            self._reply(msg, False, error={"message": f"fake-adapter 不处理 type={msg_type}"})
            return
        handler(msg)
    def _on_conn_open(self, msg: dict) -> None:
        payload = _payload(msg)
        cid = _text(payload.get("connectionId"))
        if not cid:
            log("conn.open 缺少 connectionId，拒绝")
            self._reply(msg, False, error={"message": "connectionId 不能为空"}); return
        config = payload.get("config") if isinstance(payload.get("config"), dict) else {}
        if cid in self.connections:  # 会话替换：旧句柄全部作废，否则反查会指回过期会话
            self._forget_handles(cid); log(f"conn.open {cid}：旧句柄已作废（会话替换）")
        # FORWARD 的真句柄只有 Java 知道；REVERSE 的句柄由 Java 随后 ws.opened 下发，这里先放一个
        # h-<connectionId> 占位，让映射表在 ws.opened 到达前也能认出这个连接
        handle_id = self._forward_handle(cid, config) or f"{HANDLE_PREFIX}{cid}"
        self._bind_handle(cid, handle_id, config)
        log(f"conn.open {cid} 完成，handleId={handle_id}")
        self._reply(msg, True, handleId=handle_id)
    def _forward_handle(self, cid, config):
        target_url = _text(config.get("targetUrl"))
        if not target_url:
            return None
        log(f"conn.open {cid}：请求 Java 建立正向连接 {target_url}")
        # 必须带 connectionId：正向连接此刻还没有 handleId，Java 只能靠它认领这个请求
        reply = self._request("ws.open", {"connectionId": cid, "url": target_url, "headers": {}})
        handle_id = _extract_handle_id(reply)
        if not handle_id:
            log(f"conn.open {cid}：Java 未给出 handleId，回退本地规则")
        return handle_id
    def _on_conn_reload(self, msg: dict) -> None:
        """Java 的 conn.reload 是单向通知（无 id）：更新配置即可，reply 自然不会发出去。"""
        payload = _payload(msg)
        cid = _text(payload.get("connectionId"))
        config = payload.get("config") if isinstance(payload.get("config"), dict) else {}
        entry = self.connections.get(cid) if cid else None
        if entry is None and cid:
            # 「配置先到、open 后到」在重连抖动里很常见，宽容处理但要留痕
            log(f"conn.reload 收到未登记的 connectionId={cid}，按隐式 open 处理")
            self._bind_handle(cid, f"{HANDLE_PREFIX}{cid}", config)
        elif entry:
            entry["config"] = config
        log(f"conn.reload {cid} 已应用新配置")
        # FORWARD 且新配置里有 targetUrl：Java 已经关掉旧通道并等待重建，但**它不会
        # 再发一次 conn.open**（adapterNotified 已置位）。所以重建的发起方只能是适配器。
        # 真实插件在这里也该这么做：配置变了就按新配置重新走一遍「取地址 → ws.open」。
        if entry is not None and _text(config.get("targetUrl")):
            log(f"conn.reload {cid}：配置含 targetUrl，按新配置重建正向通道")
            self._forward_handle(cid, config)
        self._reply(msg, True)
    def _on_conn_close(self, msg: dict) -> None:
        payload = _payload(msg)
        cid = _text(payload.get("connectionId"))
        released = self._forget_handles(cid); self.connections.pop(cid, None)
        log(f"conn.close {cid}（reason={_text(payload.get('reason'))}）已释放 {released} 个句柄")
        self._reply(msg, True)
    def _on_ws_opened(self, msg: dict) -> None:
        """Java 下发反向连接的 handleId —— REVERSE 模式映射的主来源：payload 同时给了
        connectionId，直接建双向映射即可，不必（也不该）去猜 handleId 的格式。
        """
        payload = _payload(msg)
        cid, handle_id = _text(payload.get("connectionId")), _text(payload.get("handleId"))
        if not cid or not handle_id:
            log(f"ws.opened 缺 connectionId/handleId，忽略：{payload!r}"); return
        self._bind_handle(cid, handle_id)
        log(f"ws.opened 已登记 {handle_id} ↔ {cid}")
    def _on_ws_closed(self, msg: dict) -> None:
        """单向通知：只留痕，不写 stdout（Java 不认、也没在等）。"""
        log(f"ws.closed handleId={_text(_payload(msg).get('handleId'))} "
            f"reason={_text(_payload(msg).get('reason'))}")
    def _on_ws_frame(self, msg: dict) -> None:
        """核心：模拟「协议解析 + 过滤」，只有 wanted 上抛，其余一律丢弃。"""
        payload = _payload(msg)
        handle_id = _text(payload.get("handleId"))
        cid = self._resolve_connection(handle_id, payload)
        if cid is None:  # 句柄没登记、连接又不在了：这一帧属于已停用的通道，丢弃
            log(f"handleId={handle_id} 未登记或连接已停用，丢弃这一帧"); return
        if self.frame_delay_ms > 0:
            time.sleep(self.frame_delay_ms / 1000.0)  # 故意卡主循环：测 Java 侧的排队行为
        frame = payload.get("data") if isinstance(payload.get("data"), dict) else {}
        text = _decode_frame(frame, handle_id)
        if text is None:
            return  # 形状不对或解不出文本：_decode_frame 已记过日志
        try:
            obj = json.loads(text)
        except ValueError:
            log(f"handleId={handle_id} 的帧不是 JSON，按非本协议数据丢弃"); return
        if not isinstance(obj, dict) or not _text(obj.get("type")):
            log(f"handleId={handle_id} 的帧缺少可用的 type 字段，丢弃"); return
        event_type = obj["type"]
        if event_type != MATCHED_EVENT_TYPE:
            # 这就是「倒排索引未命中」：只记 stderr，绝不上抛（测试会断言这一点）
            log(f"handleId={handle_id} 帧 type={event_type} 未命中触发条件，丢弃"); return
        event = {"raw": obj}
        if "echo" in obj:
            event["echo"] = obj["echo"]  # 透传原字段，供 Java 侧断言「原样带过来」
        send("event.matched", {"handleId": handle_id, "connectionId": cid,
                               "eventType": event_type, "workflowIds": [], "event": event})
        log(f"已上抛 event.matched（handleId={handle_id}, connectionId={cid}）")
        # echoFrames 是**连接级**配置（每条连接可以不同），所以必须从连接登记里取，
        # 不能读 __init__ 时那份进程级默认值 —— 后者永远是 False，回灌就永远不会发生。
        if self._echo_frames_of(cid):  # Java 的 ws.send 读扁平字段 handleId/encoding/content
            send("ws.send", {"handleId": handle_id, "encoding": _text(frame.get("encoding")) or "text",
                             "content": frame.get("content")})
            log(f"echoFrames=true，已把该帧原样回发给 handleId={handle_id}")
    def _echo_frames_of(self, cid) -> bool:
        """取该连接的 echoFrames；连接级没写就回退到进程级默认值。"""
        entry = self.connections.get(cid) or {}
        value = (entry.get("config") or {}).get("echoFrames")
        return value if isinstance(value, bool) else self.echo_frames
    def _on_handshake_check(self, msg: dict) -> None:
        log("收到 handshake.check：假适配器不实现握手校验，明确回复 ok=false")  # Java 目前从不发这条
        self._reply(msg, False, error={"message": "fake-adapter 未实现 handshake.check"})
    def _on_call_invoke(self, msg: dict) -> None:
        action = _payload(msg).get("action")  # Java 只认 type="reply"（不存在 call.reply）
        log(f"call.invoke action={action!r}（connectionId={_text(_payload(msg).get('connectionId'))}）")
        self._reply(msg, True, result={"echoedAction": action, "fake": True})
    def _on_shutdown(self, msg: dict) -> None:
        raise _Exit("收到 shutdown，正常退出")

def main() -> int:
    # 钉死编码：Windows 管道默认代码页（cp936 等）会让中文 displayName 乱码甚至抛异常
    for stream in (_PROTOCOL_OUT, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="backslashreplace", line_buffering=True)
        except Exception as exc:  # 流被换成没有 reconfigure 的对象时降级，不致命
            log(f"重配置流失败（忽略）：{exc!r}")
    adapter = FakeAdapter(load_config())
    try:
        adapter.send_hello()
        adapter.run()
    except _Exit as exc:  # EOF（孤儿防护）或 shutdown：都算正常退出
        log(str(exc)); return 0
    except BaseException as exc:  # 含 KeyboardInterrupt：绝不静默死亡
        log("未捕获异常，准备上报 error 后退出：\n" + traceback.format_exc())
        try:
            send("error", {"message": f"{type(exc).__name__}: {exc}", "detail": traceback.format_exc()})
        except Exception as send_exc:
            log(f"error 消息也没发出去：{send_exc!r}")
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
