package com.loombot.config.notify;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把每次发信的结果写进 {@code notify_log}。
 *
 * <h2>为什么写日志也要「尽力而为」</h2>
 *
 * <p>D56 定下的规则是「发信失败不得回滚业务」，这条规则有两个方向，第二个方向更隐蔽： <b>连记录发信失败这件事本身，也不能把业务拖下水</b>。如果 {@code notify_log}
 * 因为 表被锁、连接池打满、列宽溢出而写不进去，而这里选择抛出异常，那么结果就是 「日志表写不进去 → 用户注册不了」—— 一个纯粹用于排查问题的组件反而成了故障源。
 *
 * <p>所以这里吞掉所有异常，只记应用日志。代价是「log 表里可能少几条记录」， 而这个代价可以接受：{@code notify_log} 是排查的**辅助**，不是事实来源。
 *
 * <h2>为什么截断</h2>
 *
 * <p>异常消息的长度不可控（SMTP 服务器返回的原文可能很长），而列宽是固定的。 不截断的话，一次超长错误会让这条日志<b>写不进去</b>，于是恰好在那次失败时 什么记录都没有 ——
 * 最需要它的时候它不在。
 */
@Component
public class NotifyLogWriter {

    private static final Logger log = LoggerFactory.getLogger(NotifyLogWriter.class);

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private static final int MAX_RECIPIENT_LENGTH = 128;
    private static final int MAX_SUBJECT_LENGTH = 255;
    private static final int MAX_ERROR_LENGTH = 512;

    private final NotifyLogMapper mapper;

    public NotifyLogWriter(NotifyLogMapper mapper) {
        this.mapper = mapper;
    }

    public void success(String bizType, String recipient, String subject) {
        write(bizType, recipient, subject, STATUS_SUCCESS, null);
    }

    public void failure(String bizType, String recipient, String subject, Throwable cause) {
        write(bizType, recipient, subject, STATUS_FAILED, summarize(cause));
    }

    private void write(
            String bizType, String recipient, String subject, String status, String error) {
        try {
            mapper.insert(
                    IdWorker.getId(),
                    truncate(bizType, 32),
                    truncate(recipient, MAX_RECIPIENT_LENGTH),
                    truncate(subject, MAX_SUBJECT_LENGTH),
                    status,
                    truncate(error, MAX_ERROR_LENGTH));
        } catch (Exception e) {
            log.warn("写 notify_log 失败（不影响业务）: bizType={}, status={}", bizType, status, e);
        }
    }

    private static String summarize(Throwable cause) {
        if (cause == null) {
            return null;
        }
        String message = cause.getMessage();
        String summary = cause.getClass().getSimpleName();
        return message == null || message.isBlank() ? summary : summary + ": " + message;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
