package com.loom.config.notify;

import com.loom.common.notify.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 装配 {@link MailSender}：配了 SMTP 就真发；没配时默认拒绝启动，仅允许本机显式开启日志邮件。
 *
 * <h2>为什么用 {@code ObjectProvider} 探测，而不是 {@code @ConditionalOnProperty}</h2>
 *
 * <p>{@code @ConditionalOnProperty(name = "spring.mail.host")} 判断的是「这个键存不存在」， 而 <b>空字符串也算存在</b>。于是
 * application.yml 里写一句 {@code host: ${MAIL_HOST:}} （看起来完全合理：「默认空」）就会让 Boot 创建一个 host 为空的 {@code
 * JavaMailSender}， 条件成立 → 选中 SMTP 实现 → 运行时每次发信都失败。这正是一个「配置看着对、行为全错」 的经典陷阱。
 *
 * <p>Spring Boot 自己的邮件自动配置用的是 {@code containsProperty}，同样只看存在性。 所以这里换成探测 <b>bean 是否真的被创建了</b>：Boot
 * 只在 {@code spring.mail.host} 或 {@code spring.mail.jndi-name} 有值时才会注册 {@code JavaMailSender}。
 * 判断依据与结果同源，不存在「条件说是、实际没有」的空档。
 *
 * <p>日志邮件必须通过 {@code loom.notify.allow-logging-mail=true} 显式开启，避免生产环境漏配 SMTP 时泄漏验证码。
 *
 * <p>（{@code getIfAvailable()} 在这里是安全的：所有 bean 定义先注册、后实例化， 自动配置的 {@code JavaMailSender}
 * 定义在本次实例化之前就已经在了。）
 *
 * <p>本 Bean 刻意命名为 {@code loomMailSender} 而不是 {@code mailSender}：一旦配置了 {@code spring.mail.host}，Boot
 * 的 {@code MailSenderPropertiesConfiguration} 会注册一个名为 {@code mailSender} 的 {@code JavaMailSender}
 * Bean；同名 Bean 在「禁止定义覆盖」（Boot 默认）下 会让启动直接失败（BeanDefinitionOverrideException）。本 Bean
 * 只按类型注入，不依赖名字，改名没有副作用。
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public MailSender loomMailSender(
            ObjectProvider<JavaMailSender> javaMailSenderProvider,
            NotifyLogWriter notifyLogWriter,
            @Value("${spring.mail.from:}") String from,
            @Value("${spring.mail.username:}") String username,
            @Value("${loom.notify.allow-logging-mail:false}") boolean allowLoggingMail) {

        JavaMailSender javaMailSender = javaMailSenderProvider.getIfAvailable();
        if (javaMailSender == null) {
            if (!allowLoggingMail) {
                throw new IllegalStateException(
                        "未配置 SMTP，且未显式允许日志邮件。生产环境必须配置 spring.mail.host；"
                                + "本机开发可设置 ALLOW_LOGGING_MAIL=true。");
            }
            log.warn(
                    "未配置 spring.mail.host —— 邮件不会真正发出，验证码只会写进日志与 notify_log。"
                            + "这只适用于本机开发，生产环境必须配置 SMTP。");
            return new LoggingMailSender(notifyLogWriter);
        }

        // 发件人优先取 spring.mail.from，缺省用登录用户名（多数 SMTP 服务要求两者一致）。
        // 两者都空时直接启动失败：让 SMTP 服务器在第一次注册时才拒信，
        // 排查成本远高于现在说清楚。
        String sender = from.isBlank() ? username : from;
        if (sender.isBlank()) {
            throw new IllegalStateException(
                    "已配置 spring.mail.host 但缺少发件人：请设置 spring.mail.from（推荐）或 spring.mail.username。");
        }
        return new SmtpMailSender(javaMailSender, notifyLogWriter, sender);
    }
}
