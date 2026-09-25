package com.loom.config.notify;

import com.loom.common.notify.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;

/**
 * 真正的 SMTP 发信实现 —— 全工程唯一碰 SMTP 的地方。
 *
 * <h2>为什么在 {@code config} 而不是 {@code common.notify}</h2>
 *
 * <p>{@code common} 未来要抽成独立 jar，而 SMTP 是外部 IO 依赖，进去就是「共享内核带着 一个网络客户端」（D53）。接口留在 {@code
 * common.notify}，实现留在这里。
 *
 * <h2>为什么吞异常而不是往上抛</h2>
 *
 * <p>见 {@link MailSender} 的实现约定：邮件发不出去不是业务失败。注册已经成功了， 把异常抛出去只会让调用方回滚一个本该成立的注册，制造「邮件服务抖动 → 谁也别想注册」。
 *
 * <p>失败的事实不会丢：{@link NotifyLogWriter} 会把它写进 {@code notify_log}， 所以「用户说没收到」时仍然能查明是「没发」还是「发了没到」。
 *
 * <h2>为什么用 {@code SimpleMailMessage} 而不是 MimeMessage</h2>
 *
 * <p>当前两个用途（验证码、断联通知）都是纯文本，不需要附件与 HTML。用 {@code MimeMessage} 要多写 {@code MimeMessageHelper}
 * 那一套，而收益为零。需要富文本时再加，并同步把 {@link MailSender} 的注释里那条「另加方法」兑现。
 */
public class SmtpMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    private final JavaMailSender javaMailSender;
    private final NotifyLogWriter notifyLogWriter;
    private final String from;

    public SmtpMailSender(
            JavaMailSender javaMailSender, NotifyLogWriter notifyLogWriter, String from) {
        this.javaMailSender = javaMailSender;
        this.notifyLogWriter = notifyLogWriter;
        this.from = from;
    }

    @Async
    @Override
    public void sendText(String bizType, String to, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            javaMailSender.send(message);
            notifyLogWriter.success(bizType, to, subject);
            log.debug("邮件已发送: bizType={}, to={}", bizType, to);
        } catch (Exception e) {
            // 这里 log.warn 不带堆栈：SMTP 的错误信息本身就是完整的（拒绝原因、响应码），
            // 堆栈只会把真正有用的那一行淹掉。完整信息由 notify_log 之外的日志系统留档。
            log.warn("邮件发送失败: bizType={}, to={}, 原因={}", bizType, to, e.toString());
            notifyLogWriter.failure(bizType, to, subject, e);
        }
    }
}
