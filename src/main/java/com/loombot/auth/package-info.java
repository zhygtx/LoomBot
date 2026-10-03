/**
 * 认证模块 —— 注册、登录、找回密码、令牌与验证码。
 *
 * <h2>职责</h2>
 *
 * <ul>
 *   <li>注册 / 登录 / 登出 / 找回密码（邮箱 + 密码，见 docs/auth.md）
 *   <li>JWT 的签发、校验与吊销（带 {@code jti} + Redis 白名单，可强制下线）
 *   <li>邮箱验证码的生成、校验与频率限制（存 Redis，带 TTL）
 *   <li>用户授权数据的加载与缓存（角色 + 权限串 → {@code GrantedAuthority}）
 *   <li>{@code JwtAuthenticationFilter}：从 {@code Authorization: Bearer} 恢复认证上下文
 * </ul>
 *
 * <h2>包内结构</h2>
 *
 * <table border="1">
 *   <caption>分层</caption>
 *   <tr><th>包</th><th>内容</th></tr>
 *   <tr><td>{@code controller}</td><td>HTTP 接口，只做参数绑定与编排调用</td></tr>
 *   <tr><td>{@code service}</td><td>
 *       {@code AuthService}（流程编排，决定顺序）、{@code TokenService}（令牌）、
 *       {@code EmailCodeService}（验证码）、{@code UserService}（用户持久化与授权加载）</td></tr>
 *   <tr><td>{@code security}</td><td>过滤器、Bearer 解析</td></tr>
 *   <tr><td>{@code domain} / {@code mapper}</td><td>{@code sys_user} 实体与 SQL</td></tr>
 *   <tr><td>{@code dto}</td><td>请求 / 响应记录</td></tr>
 *   <tr><td>{@code AuthProperties}</td><td>模块参数（{@code loombot.auth.*}）</td></tr>
 * </table>
 *
 * <p>流程的顺序是**安全语义**而不是实现细节（例如「先验密码再看账号是否停用」决定了 攻击者能否拿登录接口探测停用账号），所以编排集中在 {@code AuthService}
 * 并逐条写明理由。
 *
 * <h2>为什么属性类也在这个包里</h2>
 *
 * <p>{@link com.loombot.auth.AuthProperties} 没有放进 {@code com.loombot.config} —— 那会让本模块依赖 {@code
 * config}，破坏下面那条边界规则。模块自己的参数跟着模块走。
 *
 * <h2>表归属</h2>
 *
 * <p>{@code sys_user} / {@code sys_user_role} 的读取归本模块（认证必须读用户）。 {@code system} 模块的 package-info
 * 里写的是「五张表被两者共用」—— 那是当前单体的 既成事实，不是一条能靠「谁 import 谁」实现的规则。明确的划分见 {@link
 * com.loombot.auth.service.UserService} 的类注释。
 *
 * <h2>未来拆分为微服务</h2>
 *
 * <p>本模块是天然的服务边界候选：认证是典型的独立服务（甚至可以直接用现成的身份提供方替代）。拆分时需要搬走的东西：
 *
 * <ul>
 *   <li>本包全部内容
 *   <li>{@code sys_user} / {@code sys_user_role}（身份与授权读取）
 *   <li>{@code config} 包下的 {@code SecurityConfig} / {@code PasswordEncoderConfig}
 * </ul>
 *
 * <h2>边界规则</h2>
 *
 * <p>只允许依赖 {@code common}。<b>不允许依赖其他业务模块</b>（{@code config} 同理， 见上面的属性类说明）。其他模块需要知道「当前用户是谁」时，通过
 * {@code common} 里的 {@link com.loombot.common.security.CurrentUser} 获取，而不是依赖本模块。
 */
package com.loombot.auth;
