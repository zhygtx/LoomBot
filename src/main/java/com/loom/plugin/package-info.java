/**
 * 插件模块 —— Python 插件的生命周期管理与进程通信。
 *
 * <h2>职责</h2>
 *
 * <ul>
 *   <li><b>定时拉取插件更新</b>（{@code git pull}），不做上传、不做 PR 审核
 *   <li>用 {@code ProcessBuilder} 拉起 Python 插件进程，通过 <b>NDJSON over stdio</b> 通信
 *   <li>进程生命周期：启动、健康检查、崩溃后指数退避重启、JVM 退出时清理子进程
 *   <li>版本管理：记录当前 commit 与上次成功运行的 commit（供失败回滚）
 *   <li>插件的元信息管理（名称、版本、依赖、所需权限）
 * </ul>
 *
 * <h2>职责边界：本模块只做「拉取 + 运行」</h2>
 *
 * <p>插件的<b>提交、审核、合并</b>由<b>独立系统</b>负责 —— 用户提交 PR，AI 自动审核并合并。 本项目（Loom）只做两件事：
 *
 * <ol>
 *   <li>定时从 Git 仓库拉取已合并的更新
 *   <li>把插件跑起来并管理其进程
 * </ol>
 *
 * <p>这样切分的好处：审核逻辑（重、易变、需要 AI 能力）与运行时（轻、稳定、需要低延迟）解耦。 审核规则变化不需要动 Loom，Loom 的稳定性也不受审核系统影响。
 *
 * <h2>为什么是独立进程而不是 GraalPy</h2>
 *
 * <p>独立进程方案让插件能使用完整 Python 生态（含 C 扩展），且崩溃隔离、可单独重启。 详见 {@code docs/decisions.md} 的 D8。
 *
 * <h2>通信协议的硬性约定</h2>
 *
 * <p><b>stdout 只跑协议</b>（一行一条 JSON），日志一律走 stderr。 插件里任何一句 {@code print()} 都会撕碎协议 —— 插件模板必须重定向 {@code
 * sys.stdout}。
 *
 * <h2>安全警示</h2>
 *
 * <p>从 Git 仓库拉取代码并执行，等同于<b>任意代码执行</b>。相比 zip 上传模式多了可审计性（commit hash 可追溯）与可复现性（可指定
 * tag），但<b>风险等级没有变化</b> —— 容器隔离仍然必要（正好复用已引入的 {@code docker-java}）。在做到这一点之前，插件目录的写权限应当视为与服务器 root
 * 权限等价。
 *
 * <h2>边界规则</h2>
 *
 * <p>只允许依赖 {@code common}。接收 WS 消息时依赖 {@code connection} 暴露的接口， 而不是直接操作 {@code WebSocketSession}。
 */
package com.loom.plugin;
