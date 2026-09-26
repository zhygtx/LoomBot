package com.loom.auth.domain;

/**
 * 邮箱验证码的使用场景。
 *
 * <h2>为什么验证码要分场景</h2>
 *
 * <p>同一串验证码如果在「注册」和「找回密码」之间通用，那么一次注册流程拿到的验证码就能用来重置其他用户的密码 —— 两条链路的风险等级完全不同，必须隔开。场景直接拼进 Redis 键（见
 * {@code EmailCodeService}），互不可见。
 *
 * <h2>两个场景在「用户不存在时」的行为刻意不同</h2>
 *
 * <table border="1">
 *   <caption>场景差异</caption>
 *   <tr><th>场景</th><th>邮箱/用户不存在时</th><th>原因</th></tr>
 *   <tr><td>{@link #REGISTER}</td><td>直接报错（邮箱已被注册）</td>
 *       <td>注册接口本来就必须告诉用户「这个邮箱用过了」，否则他无法理解失败原因。
 *           既然注册接口会说，验证码接口瞒着没有意义。</td></tr>
 *   <tr><td>{@link #RESET_PASSWORD}</td><td>静默不发信，但返回成功</td>
 *       <td>否则这个接口就成了免费的「某邮箱是否注册过」查询器。
 *           代价是邮箱写错时用户得不到提示 —— 这是刻意的取舍，见 docs/auth.md。</td></tr>
 * </table>
 */
public enum EmailCodeScene {

    /** 注册新用户。 */
    REGISTER("注册"),

    /** 忘记密码后重置密码。 */
    RESET_PASSWORD("重置密码");

    private final String label;

    EmailCodeScene(String label) {
        this.label = label;
    }

    /** 中文名，用于邮件文案。 */
    public String label() {
        return label;
    }
}
