package com.loom.common.security;

/**
 * 当前调用者的身份。
 *
 * <h2>为什么这个类型在 {@code common}，而不在 {@code auth}</h2>
 *
 * <p>{@code auth} 的边界规则写着「其他模块需要知道当前用户是谁时，应通过 {@code common} 里的当前用户上下文获取， 而不是依赖本模块」。这条规则要成立，{@link
 * CurrentUser} 能拿出来的东西就必须定义在 {@code common} 里 —— 否则 {@code connection} 想读一下调用者 ID 就得依赖 {@code
 * auth}，规则当场作废。
 *
 * <p>所以这里放的是**身份的载体**（回答「谁在调」），不是**身份的业务逻辑** （注册、登录、权限串匹配全在 {@code auth}）。
 *
 * <h2>为什么是 record 而不是实体</h2>
 *
 * <p>它不映射任何表，只是过滤器从 JWT 里解析出来后塞进 {@code SecurityContext} 的一个值。 不带可变状态、不需要 setter，因此不适用 D35 里那条「只有
 * MyBatis-Plus 实体才用 Lombok」的例外。
 *
 * <p>{@code account} / {@code email} 是**令牌签发那一刻的快照**。用户改了账号或邮箱之后， 旧令牌里的值会直到过期才更新 ——
 * 这换取的是「每个请求不必查一次库」。 需要绝对最新的值时必须回库（见 {@code AccountController#me}）。
 *
 * @param id 用户 ID，对应 {@code sys_user.id}
 * @param account 账号（唯一标识，不用于登录）
 * @param email 邮箱（登录凭据）
 */
public record AuthUser(Long id, String account, String email) {}
