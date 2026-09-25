/**
 * 连接模块 —— 通用 WebSocket 连接的建立、维持与消息分发。
 *
 * <h2>核心定位</h2>
 *
 * <p>本模块是<b>协议无关的 WS 传输层</b>。它只知道「有一条连接、要收发字节」，<b>完全不知道 OneBot 协议长什么样</b>。协议解析全部下沉到 Python 适配器。
 *
 * <p>新增一个机器人平台 = 加一条 {@code ws_connection} 记录 + 写一个 Python 适配器， Java 侧不需要重新编译。
 *
 * <h2>责任分界（这里最容易搞混，务必分清）</h2>
 *
 * <p>早期设计写的是「Java 负责握手 / 心跳 / 重连 / 关闭」，<b>这个说法不准确</b>：
 *
 * <table border="1">
 *   <caption>职责划分</caption>
 *   <tr><th></th><th>负责方</th><th>说明</th></tr>
 *   <tr><td>WS 通道本身</td><td><b>Java</b></td>
 *       <td>持有服务端（{@code /ws/**}）与客户端，管连接生命周期与重连</td></tr>
 *   <tr><td>如何得到这个通道</td><td><b>适配器</b></td>
 *       <td>正向连接的 URL / 请求头由适配器算出来（{@code ws.open}）；
 *           反向连接由平台按 Java 下发的路径连进来</td></tr>
 *   <tr><td>心跳</td><td><b>适配器</b></td>
 *       <td>心跳是协议概念（{@code {"type":"heartbeat"}}），Java 不认识它。
 *           适配器负责在帧层面过滤，命中触发条件才上抛 {@code event.matched}</td></tr>
 *   <tr><td>握手校验</td><td><b>Java</b></td>
 *       <td>但校验规则由适配器在 {@code hello} 里声明（{@code x-handshake}），
 *           所以 Java 不需要知道密钥字段叫什么</td></tr>
 * </table>
 *
 * <p>推论：<b>「适配器崩溃不会断连」只对一半。</b>已建立的通道确实还在， 但适配器重启前的帧没人消费，也无法发起新连接。
 *
 * <h2>包结构</h2>
 *
 * <ul>
 *   <li>{@code domain} —— 实体与运行时值对象
 *   <li>{@code mapper} —— 持久化访问
 *   <li>{@code dto} —— 对外的请求 / 响应模型
 *   <li>{@code service} —— CRUD、接入路径生成、config 掩码
 *   <li>{@code controller} —— HTTP 接口
 *   <li>{@code manager} —— <b>运行时状态机的唯一权威</b>（连接状态、重连、通道生命周期）
 *   <li>{@code adapter} —— 与 Python 适配器进程的会话（IPC 消息分发、请求-回复）
 *   <li>{@code handshake} —— 反向连接握手校验（内置校验器 + 注册表）
 *   <li>{@code ws} —— Spring WebSocket 端点注册与反向帧处理
 * </ul>
 *
 * <h2>表归属</h2>
 *
 * <p>{@code ws_connection}，脚本见 {@code V2__init_ws_connection.sql}。
 *
 * <h2>边界规则</h2>
 *
 * <p>允许依赖 {@code common} 与 {@code runtime}（基础模块）。<b>不得依赖其他业务模块</b> （{@code auth} / {@code system} /
 * {@code plugin} / {@code workflow}）。这条规则原本由 {@code ArchitectureTest} 强制，该测试已于 2026-09-25 移除（D59）。
 *
 * <p>方向不落在数据库里：{@code ws_connection} 表没有 {@code direction} 列， 方向由 {@code connection_type}
 * 对应的适配器声明。这样「加一个平台」不需要改表结构。
 */
package com.loom.connection;
