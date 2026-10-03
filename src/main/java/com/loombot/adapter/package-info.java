/**
 * Java Adapter Runtime。
 *
 * <p>负责加载被启用连接引用的 Adapter Plugin 版本，接收插件产出的 {@code node.matched}， 查 Redis 触发索引并写工作流任务
 * Stream。协议与连接方式属于 Adapter Plugin，不属于本模块。
 */
package com.loombot.adapter;
