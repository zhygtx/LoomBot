/**
 * 插件模块 —— Python 插件的生命周期管理与进程通信。
 *
 * <h2>职责</h2>
 *
 * <ul>
 *   <li><b>同步公共插件仓库</b>并读取 {@code index.json} 清单
 *   <li>登记全部插件、全部版本、能力、连接类型、节点和依赖信息
 *   <li>启动时先比较仓库 commit hash，未变化则跳过扫描
 *   <li>向 runtime / connection / adapter 暴露只读的 {@code PluginCatalog}
 * </ul>
 *
 * <h2>职责边界：本模块只做「同步 + 元信息」</h2>
 *
 * <p>插件的<b>提交、审核、合并</b>由<b>独立系统</b>负责 —— 用户提交 PR，AI 自动审核并合并。
 *
 * <p>⚠️ <b>插件进程本身不由本模块托管</b>。Adapter Plugin 由 {@code com.loombot.adapter} 下的 Adapter Runtime
 * 按被启用连接引用的版本启动，Worker 由后续 runtime 调度器按版本预热。
 *
 * <h2>分发形态：从公共仓库同步版本目录</h2>
 *
 * <p>远程配置 {@code loombot.plugin.repository-url} 时，启动阶段先 fetch / pull 公共仓库； 本地开发可以只配置 {@code
 * local-path}，直接扫描已有插件目录。版本目录一旦登记就视为不可变， 同版本内容变化必须拒绝并发布新版本。
 *
 * <p>⚠️ 但仍必须保留<b>来源可追溯</b>：清单里每个版本要带 commit hash + 审核记录引用。
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
 * <p>执行插件代码等同于<b>任意代码执行</b>。相比 zip 上传模式多了可审计性（commit hash 可追溯）， 但<b>风险等级没有变化</b> —— 容器隔离仍然必要。
 *
 * <p>⚠️ <b>「AI + 人工审核 ≈ 安全」这个推论不成立</b>：审核提升的是代码质量， 不是沙箱强度。而且 {@code index.json} 是<b>单点信任</b> ——
 * 它被篡改就等于能下发任意代码。
 *
 * <p>⚠️ <b>安装时的清单自省进程会 import 插件代码（导入即执行）</b>， 所以它必须先沙箱化，并提供 {@code [manifest] mode = "static"}
 * 逃生通道。
 *
 * <h2>边界规则</h2>
 *
 * <p>只允许依赖 {@code common} 与 {@code runtime}（基础模块）。Adapter Runtime 通过 {@code PluginCatalog}
 * 读取版本元数据，不直接依赖插件实体。
 */
package com.loombot.plugin;
