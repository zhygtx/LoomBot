/**
 * 运行时基础设施 —— Python 进程宿主与 IPC。
 *
 * <h2>为什么是独立的基础模块</h2>
 *
 * <p>适配器宿主要被 {@code connection} 和 {@code workflow} <b>共用</b>。 如果把它留在 {@code plugin} 模块里，就会产生 {@code
 * connection → plugin} 依赖， 撞上「业务模块之间不得互相依赖」那条架构约束（见 {@code ArchitectureTest}）。
 *
 * <p>所以它与 {@code common} 同级，属于**基础模块**： 业务模块可以依赖它，它不得依赖任何业务模块。
 *
 * <h2>内容</h2>
 *
 * <ul>
 *   <li>{@code ipc} —— Java ↔ Python 的消息信封与 NDJSON 编解码
 *   <li>{@code process} —— Python 进程宿主（启动、stderr 转日志、优雅关闭）
 * </ul>
 *
 * <h2>协议约定（与适配器规范一致）</h2>
 *
 * <ul>
 *   <li>传输层：NDJSON，一行一条 JSON，UTF-8
 *   <li><b>stdout 只跑协议</b>，日志一律走 stderr
 *   <li>协议违规要<b>大声报错</b>并记录原始行，不静默丢弃
 * </ul>
 *
 * <h2>孤儿进程防护</h2>
 *
 * <p>JVM 被 {@code kill -9} 时 shutdown hook 不执行，Python 子进程会变孤儿。 主要的防护不在本模块，而是约定**子进程读 stdin 返回 EOF
 * 就自杀** —— JVM 死亡时管道自动关闭，子进程立刻能感知，比依赖 shutdown hook 可靠得多。 容器层面再加 {@code --init}（tini）兜底。
 */
package com.loom.runtime;
