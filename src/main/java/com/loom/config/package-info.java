/**
 * 全局技术配置 —— 与具体业务无关的框架层装配。
 *
 * <h2>内容</h2>
 *
 * <ul>
 *   <li>{@code SecurityConfig} —— Spring Security 过滤器链、密码编码器
 *   <li>（待补）MyBatis-Plus 配置：分页插件、字段自动填充、逻辑删除
 *   <li>（待补）Redis 配置：序列化方式、缓存管理器
 *   <li>（待补）WebMvc 配置：跨域、拦截器注册
 * </ul>
 *
 * <h2>与各业务模块 {@code config} 子包的区别</h2>
 *
 * <p>本包只放<b>应用级</b>配置。某个模块专属的配置（例如插件模块的线程池参数） 应放在该模块自己的包内，避免全局配置随业务膨胀。
 *
 * <p>例外：Spring Security 的过滤器链虽然是全局的，但它服务于 {@code auth} 模块的语义。 这里先放在 config 下，等 auth 模块成型后可以整体迁移过去。
 */
package com.loom.config;
