/**
 * 全局技术配置 —— 与具体业务无关的框架层装配。
 *
 * <h2>内容</h2>
 *
 * <ul>
 *   <li>{@code SecurityConfig} —— Spring Security 过滤器链、放行清单、CORS、JWT 过滤器挂载
 *   <li>{@code PasswordEncoderConfig} —— BCrypt 密码编码器（**刻意与 SecurityConfig 分开**， 否则会与 auth
 *       模块构成循环依赖，见该类的注释与 environment.md 第 28 条）
 *   <li>{@code CorsProperties} —— 跨域来源白名单（{@code loom.cors.*}）
 *   <li>{@code MybatisPlusConfig} —— 分页插件与硬上限
 *   <li>{@code AdapterProperties} / {@code ConnectionConfig} —— 适配器与连接
 *   <li>{@code notify} 子包 —— 邮件的**实现**：{@code SmtpMailSender} / {@code LoggingMailSender} / {@code
 *       NotifyLogWriter}。接口在 {@code common.notify}，实现放在这里是因为 {@code common} 未来要抽成独立 jar，不该带着 SMTP
 *       与数据库这种外部 IO（D53）
 * </ul>
 *
 * <h2>与各业务模块 {@code config} 子包的区别</h2>
 *
 * <p>本包只放<b>应用级</b>配置。某个模块专属的配置（例如插件模块的线程池参数） 应放在该模块自己的包内，避免全局配置随业务膨胀。 同理，{@code loom.auth.*} 的绑定类在
 * {@code com.loom.auth} 里 —— 放这里会让 {@code auth} 反过来依赖 {@code config}，破坏它的边界规则。
 *
 * <p>例外：Spring Security 的过滤器链虽然是全局的，但它服务于 {@code auth} 模块的语义。 这里先放在 config 下，等 auth 模块成型后可以整体迁移过去。
 * 注意本包对 {@code auth} 是**单向**依赖（挂载它的过滤器），{@code auth} 不反过来依赖本包。
 */
package com.loom.config;
