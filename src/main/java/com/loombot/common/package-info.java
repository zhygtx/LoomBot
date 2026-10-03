/**
 * 共享内核 —— 跨模块复用的基础设施，不含任何业务概念。
 *
 * <h2>内容</h2>
 *
 * <ul>
 *   <li>{@code api} —— 统一响应结构、分页结构、全局错误码
 *   <li>{@code exception} —— 业务异常、全局异常处理、401/403 统一出口
 *   <li>{@code trace} —— TraceId 上下文与过滤器
 *   <li>{@code util} —— 无业务含义的通用工具
 * </ul>
 *
 * <h2>边界规则</h2>
 *
 * <p><b>本包不允许依赖任何业务模块</b>（{@code auth} / {@code system} / {@code connection} / {@code plugin} /
 * {@code workflow}）。反向依赖是允许的：业务模块可以依赖 common。
 *
 * <p>这条规则原本由 {@code ArchitectureTest}（ArchUnit）强制，破坏时构建失败。该测试已于 2026-09-25 全部移除（见 {@code
 * docs/decisions.md} D59），所以它现在只是<b>约定</b>：破坏它不会再让构建失败。
 *
 * <h2>未来拆分</h2>
 *
 * <p>拆微服务时，本包适合直接抽成一个独立的 {@code loombot-common} jar，各服务共同依赖。 因此这里<b>不能出现任何业务实体</b>（如 User、Plugin）——
 * 那会导致所有服务被迫 依赖同一份业务模型，是微服务拆分失败最常见的原因。
 */
package com.loombot.common;
