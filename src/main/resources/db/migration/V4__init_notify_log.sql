-- ============================================================================
--  V4 · 通知发送日志表
-- ----------------------------------------------------------------------------
--  为什么需要这张表（docs/decisions.md D56 的落地）：
--
--  ★ 「没收到邮件」是排查成本最高的一类问题，因为它有两个完全不同的原因
--      1) 我们根本没发（业务没触发、地址写错、被静默跳过）
--      2) 发了但没送达（SMTP 拒收、进了垃圾箱）
--    没有这张表，这两种情况在用户嘴里是同一句话，你只能靠猜。
--    有了它，「查一下 notify_log」就能立刻把范围砍掉一半。
--
--  ★ 记录「失败摘要」而不是完整堆栈
--      SMTP 的异常信息里可能带收件人、服务器地址。列宽 512 足够定位问题，
--      又不会把日志表撑成一个堆栈仓库。
--
--  ★ 本表**不参与业务事务**（D56：发信失败不得回滚业务）
--      注册成功但验证码没发出去 → 是「重发」问题，不是「注册失败」。
--      所以写入是尽力而为：写失败只记应用日志，绝不向上抛。
--
--  ★ 表里没有任何业务词汇（不出现 sys_user / ws_connection / 注册 / 断联）
--      biz_type 只存一个业务方自己定义的字符串（如 REGISTER_CODE），
--      本表不理解它的含义 —— 与 D53 的「机制 / 策略」划分一致。
--
--  注意：日志表刻意**不带** deleted / create_by / update_by。
--        它不是业务数据，不会被逻辑删除，也没有「谁改的」语义 ——
--        按 docs/database.md 的全局约定照抄审计字段只会造出永远为 NULL 的列。
-- ============================================================================

CREATE TABLE `notify_log` (
    `id`          BIGINT       NOT NULL                COMMENT 'ID（雪花）',
    `biz_type`    VARCHAR(32)  NOT NULL                COMMENT '业务类型，由调用方定义，如 REGISTER_CODE / RESET_PASSWORD_CODE',
    `recipient`   VARCHAR(128) NOT NULL                COMMENT '收件人邮箱',
    `subject`     VARCHAR(255)     NULL                COMMENT '主题',
    `status`      VARCHAR(16)  NOT NULL                COMMENT '发送结果：SUCCESS / FAILED',
    `error`       VARCHAR(512)     NULL                COMMENT '失败摘要，成功时为 NULL',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_recipient` (`recipient`),
    KEY `idx_biz_type` (`biz_type`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '通知发送日志（机制层，不含业务语义）';
