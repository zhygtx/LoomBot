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
 * <h2>⚠️ 实现约定（实现时不要违背）</h2>
 *
 * <ol>
 *   <li><b>必须异步</b>：SMTP 超时可达数十秒，绝不能阻塞连接状态机或注册接口。 实现类应标注 {@code @Async}（{@code LoomApplication} 已有
 *       {@code @EnableAsync}）。
 *   <li><b>失败不得抛出业务异常</b>：邮件发不出去<b>不是</b>业务失败。注册成功但验证码没送达， 那是「重发」问题，不是「注册失败」。抛异常会导致「邮件服务抖动 →
 *       用户注册不了」。 实现应吞掉异常并记录 {@code notify_log}。
 *   <li><b>入参只允许基本类型 / String</b>：这是「{@code common} 不得依赖业务模块」这条规则的必然结果。它同时也是好事 —— 逼着调用方把「要发什么」想清楚。
 * </ol>
 *
 * <h2>TODO(notify): 落地清单</h2>
 *
 * <p>目前这是<b>唯一的入口</b>，尚无实现。按顺序做：
 *
 * <ol>
 *   <li>加 {@code spring-boot-starter-mail}。<b>注意用 starter 而不是只加库</b> —— Boot 4 的自动配置拆分后，只加 {@code
 *       jakarta.mail} 会静默不生效（见 {@code environment.md} 的同类教训）。
 *   <li>写 {@code SmtpMailSender} 实现，放 {@code com.loom.config}（唯一碰 SMTP 的地方）。
 *   <li>建 {@code notify_log} 表：收件人、模板、主题、状态、错误摘要、时间。 <b>没有它，用户说「没收到邮件」时你无法区分「没发」还是「发了没到」</b>。
 *   <li>模板放 {@code classpath:templates/notify/*.html}，不要做模板管理界面。
 *   <li>接两个调用方：{@code auth} 的注册验证码、{@code connection} 的断联通知。
 * </ol>
 *
 * <p>断联通知的具体触发规则见 {@code decisions.md} 的 D54（跃迁触发 + 静默窗口） 与 D55（无消息检测的两种语义，实现前必须二选一）。
 */
public interface MailSender {

    /**
     * 发送一封纯文本邮件。
     *
     * <p>实现必须<b>异步且不抛业务异常</b>：失败只记日志与 {@code notify_log}。
     *
     * @param to 收件人地址
     * @param subject 主题
     * @param text 正文（纯文本；需要富文本时另加方法，不要在这里塞 HTML 字符串）
     */
    void sendText(String to, String subject, String text);
}
