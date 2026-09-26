package com.loom.common.notify;

/**
 * 邮件发送能力。
 *
 * <h2>为什么接口在 {@code common}，实现却在 {@code config}</h2>
 *
 * <p>本接口是「机制」而非「策略」—— 它只回答<b>怎么发</b>，不回答<b>什么时候发</b>。 判据很硬：这个包里<b>不允许出现任何业务词汇</b>（{@code
 * WsConnection}、{@code sys_user}…）。 一旦某个候选类里冒出了业务实体名，说明它放错了层，应该回到各自的业务模块去。
 *
 * <p>反过来，「什么算断联」「验证码多久过期」是<b>业务规则</b>，属于 {@code auth} 与 {@code connection} 自己，不由本层判断。
 *
 * <h2>为什么不建独立的 {@code notify} 模块（D53）</h2>
 *
 * <p>因为 {@code auth} 和 {@code connection} 都要用它 —— 那样它就成了「第二个共享内核」。 而放进 {@code common} 又带着 SMTP 这种外部
 * IO（{@code common} 未来要抽成独立 jar）。 <b>两边都不合适，说明分类维度选错了</b>：不该按「功能」切，该按「机制 / 策略」切。
 *
 * <p>所以 <b>接口在这里，实现不在</b>：SMTP 与 {@code notify_log} 的落地类放在 {@code com.loom.config.notify}（全工程唯一碰
 * SMTP 的地方）。本包保持「只有技术词汇、 没有任何 IO」—— 将来抽成 {@code loom-common} jar 时可以直接搬走。
 *
 * <h2>⚠️ 实现约定（实现时不要违背）</h2>
 *
 * <ol>
 *   <li><b>必须异步</b>：SMTP 超时可达数十秒，绝不能阻塞连接状态机或注册接口。 实现类标注 {@code @Async}（{@code LoomApplication} 已有
 *       {@code @EnableAsync}）。
 *   <li><b>失败不得抛出业务异常</b>：邮件发不出去<b>不是</b>业务失败。注册成功但验证码没送达， 那是「重发」问题，不是「注册失败」。抛异常会导致「邮件服务抖动 →
 *       用户注册不了」。 实现吞掉异常并记录 {@code notify_log}。
 *   <li><b>入参只允许基本类型 / String</b>：这是「{@code common} 不得依赖业务模块」这条规则的必然结果。它同时也是好事 —— 逼着调用方把「要发什么」想清楚。
 * </ol>
 *
 * <h2>落地情况（原 TODO 清单）</h2>
 *
 * <ol>
 *   <li>✅ {@code spring-boot-starter-mail}（注意用 starter 而不是只加库，见 {@code environment.md} 第 20 条）
 *   <li>✅ {@code SmtpMailSender} 实现，在 {@code com.loom.config.notify}
 *   <li>✅ {@code notify_log} 表（{@code V1__bootstrap_schema.sql}）：收件人、业务类型、主题、状态、错误摘要、时间
 *   <li>⬜ 模板放 {@code classpath:templates/notify/*.html} —— 目前是纯文本，两个调用方都还不需要富文本
 *   <li>🔶 调用方：{@code auth} 的注册 / 重置密码验证码<b>已接</b>；{@code connection} 的断联通知<b>未接</b> （触发规则见 {@code
 *       decisions.md} D54 / D55）
 * </ol>
 */
public interface MailSender {

    /**
     * 发送一封纯文本邮件。
     *
     * <p>实现必须<b>异步且不抛业务异常</b>：失败只记日志与 {@code notify_log}。
     *
     * @param bizType 业务类型，由调用方定义（如 {@code REGISTER}），本层<b>不理解它的含义</b>， 只原样写进 {@code
     *     notify_log}。之所以要这个参数而不是事后去猜：用户说「没收到验证码」时， 第一个要回答的问题是「哪一类邮件、发的哪一次」。
     * @param to 收件人地址
     * @param subject 主题
     * @param text 正文（纯文本；需要富文本时另加方法，不要在这里塞 HTML 字符串）
     */
    void sendText(String bizType, String to, String subject, String text);
}
