/**
 * 认证授权模块 —— 登录、令牌、权限判定。
 *
 * <h2>职责</h2>
 *
 * <ul>
 *   <li>登录 / 登出、令牌签发与校验
 *   <li>加载用户权限串集合并缓存（Redis）
 *   <li>提供 {@code @ss.hasPermi(...)} 自定义鉴权 evaluator
 *   <li>权限串的分段通配匹配（{@code *:*:*} / {@code plugin:*:*}）
 * </ul>
 *
 * <h2>未来拆分为微服务</h2>
 *
 * <p>本模块是天然的服务边界候选：认证是典型的独立服务（甚至可以直接用现成的 身份提供方替代）。拆分时需要搬走的东西：
 *
 * <ul>
 *   <li>本包全部内容
 *   <li>{@code sys_user} / {@code sys_role} / {@code sys_permission} 及两张关联表
 *   <li>{@code config} 包下的 {@code SecurityConfig}
 * </ul>
 *
 * <h2>边界规则</h2>
 *
 * <p>只允许依赖 {@code common}。<b>不允许依赖其他业务模块</b> —— 其他模块需要知道 「当前用户是谁」时，应通过 {@code common}
 * 里的当前用户上下文获取，而不是依赖本模块。
 */
package com.loom.auth;
