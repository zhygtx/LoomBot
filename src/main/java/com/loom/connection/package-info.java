/**
 * 连接控制面。
 *
 * <p>Java 保存连接的期望状态（MySQL），通过 AdapterControlClient 调用 Python Adapter Host，并把 Adapter
 * 返回的实际状态聚合给前端。Java 不持有 socket、 不注册反向 WebSocket handler、不加载 Adapter Plugin。
 */
package com.loom.connection;
