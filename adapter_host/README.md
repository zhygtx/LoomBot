# 适配器监管器

适配器监管器负责插件工作进程、连接生命周期、反向 WebSocket 路由和统一事件投递。Java 控制面只保存连接定义并发送期望状态；插件代码不会在 Java 进程内加载。

## 启动

```powershell
python -m pip install -r adapter_host/requirements.txt
$env:ADAPTER_PLUGIN_ROOT = "plugins"
$env:ADAPTER_CONTROL_TOKEN = "loom-dev-adapter-token"
$env:ADAPTER_REDIS_URL = "redis://localhost:6379/0"
python -m adapter_host.main
```

控制接口默认监听 `127.0.0.1:9100`，反向 WebSocket 网关默认监听 `127.0.0.1:9000`。所有控制请求都要带 `X-Adapter-Token`。

## 控制接口

连接期望状态使用 `POST /internal/desired/apply`，完整快照使用 `POST /internal/desired/snapshot`，删除连接使用 `DELETE /internal/desired/{connectionId}`。状态读取使用 `GET /internal/observations`，动作调用使用 `POST /internal/actions/{connectionId}`。

单连接期望状态示例：

```json
{
  "connectionId": 11,
  "revision": 3,
  "enabled": true,
  "adapter": {"package": "napcat-adapter", "version": "1.0.3", "type": "napcat"},
  "transport": {"direction": "REVERSE", "endpointId": "/ws/opaque-path"},
  "pluginPath": "plugins/napcat-adapter",
  "entryPoint": "main.py",
  "config": {"token": "..."},
  "configHash": "sha256:..."
}
```

`revision` 必须单调递增。相同版本和相同配置重复提交是幂等操作；旧版本不会覆盖新版本。完整快照中缺少的连接会被删除。

观测状态包括 `DISABLED`、`PENDING`、`STARTING`、`CONNECTING`、`LISTENING`、`ONLINE`、`RETRY_WAIT`、`DEGRADED`、`STOPPING` 和 `FAILED`。监管器不可达不伪装成连接状态，Java 单独记录 `runtimeReachable`。

## 日志

适配器监管器同时把日志写到标准错误和 `logs/adapter-host.log`（UTF-8）。Java 托管本地进程时，
这些行也会转发到 Java 日志，并带 `[adapter-host]` 前缀；插件工作进程的日志还会带
`[worker:<plugin_key>]` 前缀。

```powershell
Get-Content .\logs\adapter-host.log -Wait -Tail 100
```

## 插件工作进程

每个插件版本和适配器类型使用独立工作进程；同一进程可承载该类型的多条连接。监管器与工作进程通过标准输入输出传输版本化 JSONL 消息，日志只能写标准错误。工作进程入口必须导出 `create_adapter(host)`。

适配器生命周期方法是 `start(ctx)`、`on_session(ctx, session)`、`invoke(ctx, action, params)` 和 `stop(ctx, reason)`。上下文只提供配置、受控 HTTP/正向 WebSocket、事件发送器和会话能力。插件不得访问 Redis、数据库或 FastAPI 对象。

## 反向 WebSocket

反向端点由 Java 为每条连接生成，例如：

```text
ws://127.0.0.1:9000/ws/opaque-path
```

URL 和 Token 是两个独立字段：平台配置的 URL 填上面的传输地址，Token 填插件配置里的 `token`。
网关只负责升级、路由、文本和二进制帧转发，不读取、不解释协议 token；插件按自己的协议从
`Authorization: Bearer` 头或 `access_token` 查询参数校验。

## 事件

插件通过 `ctx.emit_node(nodeKey, payload)` 发送事件。监管器会校验节点声明并补齐 `eventId`、`traceId`、连接版本和适配器类型，然后写入 `workflow:task:v1:events` Redis Stream。投递至少一次，下游必须按 `eventId` 幂等。

## 当前示例

- `napcat-adapter`：NapCat/OneBot 11 反向 WebSocket，节点包括 `napcat.message.group`、`napcat.message.private`、`napcat.notice` 等。
- `qq-official-adapter`：QQ 官方机器人正向 Gateway，负责 Token、Gateway、Identify、心跳、重连和事件节点转换。
