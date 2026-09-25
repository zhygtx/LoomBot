package com.loom.config.notify;

import com.loom.common.notify.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * 「发信」降级为本机日志的实现。
 *
 * <h2>为什么需要它，而不是在没配 SMTP 时直接报错</h2>
 *
 * <p>本机开发时你不会有可用的 SMTP：要么没账号，要么不想让开发环境的注册流程真的往外发信。 如果此时让应用启动失败，代价是「每个想跑起来的人都得先搞一个邮箱」；如果让它静默不发，
 * 代价是「点了发送验证码，什么都没发生，也没有任何线索」。
 *
 * <p>这个实现给出第三种结果：<b>流程完整跑通，验证码以 WARN 级别打进日志</b>。 开发者从控制台抄 6 位数字就能继续走完注册 / 找回密码。
 *
 * <h2>为什么日志级别是 WARN</h2>
 *
 * <p>因为验证码出现在日志里本身就是一件「不该在生产发生」的事。用 INFO 会被 「日志里什么都有」淹没；用 WARN 才能在成千上万行里被一眼看到，也才配得上 「未配置
 * SMTP」这个配置缺陷的严重程度。
 */
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    private final NotifyLogWriter notifyLogWriter;

    public LoggingMailSender(NotifyLogWriter notifyLogWriter) {
        this.notifyLogWriter = notifyLogWriter;
    }

    @Async
    @Override
    public void sendText(String bizType, String to, String subject, String text) {
        log.warn(
                """

                ==================== 邮件未真正发送（未配置 spring.mail.host） ====================
                收件人: {}
                业务类型: {}
                主题: {}
                --------------------------------------------------------------------------------
                {}
                ================================================================================
                """,
                to,
                bizType,
                subject,
                text);
        // 状态记 SUCCESS：从本实现的角度看，它「完成了自己的职责」（把内容交付给开发者）。
        // 记 FAILED 会让「本机开发期间 notify_log 全是红的」，那才是真的误导。
        notifyLogWriter.success(bizType, to, subject);
    }
}
