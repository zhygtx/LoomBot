/**
 * 连接模块 —— 通用 WebSocket 连接的建立、维持与消息分发。
 *
 * <h2>核心定位</h2>
 *
 * <p>本模块是<b>协议无关的 WS 传输层</b>。它只知道「有一条连接、要收发字节」， <b>完全不知道 OneBot 协议长什么样</b>。协议解析全部下沉到 Python 插件。
 *
 * <p>这是整个重构的核心分界线：新增一个机器人平台 = 加一行 {@code ws_connection} 记录 + 写一个 Python 插件，Java 侧不需要重新编译。
 *
 * <h2>职责</h2>
 *
 * <ul>
 *   <li>读 {@code ws_connection} 表，按 {@code direction} 起连接：
 *       <ul>
 *         <li>{@code REVERSE} —— 本应用作服务端，在 {@code path} 上注册 {@code WebSocketHandler}
 *         <li>{@code FORWARD} —— 本应用作客户端，用 {@code StandardWebSocketClient} 连 {@code url}
 *       </ul>
 *   <li>连接生命周期：握手鉴权、心跳、断线重连（指数退避）、优雅关闭
 *   <li>两个方向汇入同一个 {@code PluginChannel} 抽象，向上层屏蔽来源差异
 *   <li>连接状态的运行时视图（哪些连接在线、收发字节数、最后心跳时间）
 * </ul>
 *
 * <h2>表归属</h2>
 *
 * <p>{@code ws_connection}（设计见 {@code docs/database.md}，脚本待补 {@code V2__init_ws_connection.sql}）。
 *
 * <h2>边界规则</h2>
 *
 * <p>只允许依赖 {@code common}。需要把消息交给插件时，通过 {@code plugin} 模块暴露的 接口调用，<b>不要直接引用 {@code plugin} 的实体或
 * Mapper</b>。
 */
package com.loom.connection;
