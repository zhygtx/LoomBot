package com.loombot.auth.service;

import com.loombot.auth.AuthProperties;
import com.loombot.auth.domain.EmailCodeScene;
import com.loombot.common.api.ErrorCode;
import com.loombot.common.exception.BusinessException;
import com.loombot.common.notify.MailSender;
import com.loombot.system.service.SystemConfigService;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 邮箱验证码的生成、校验与频率限制。
 *
 * <h2>为什么验证码放 Redis 而不是建表</h2>
 *
 * <p>验证码有三个天然属性：**会过期**、**用完即弃**、**只有一个值**。Redis 的 TTL 与 {@code DEL}
 * 正好一一对应这三件事。放数据库则要自己写「过期判断」并在查询里处处记得带上它 —— 忘记带一次，过期的验证码就永久有效，而且这种 bug 不会报错。
 *
 * <h2>三道限制，缺一不可</h2>
 *
 * <table border="1">
 *   <caption>限制</caption>
 *   <tr><th>限制</th><th>防的是什么</th><th>不做会怎样</th></tr>
 *   <tr><td>冷却时间（同邮箱同场景）</td><td>被当成免费的发信机</td>
 *       <td>接口一被人循环调用，你的域名立刻进垃圾邮件黑名单</td></tr>
 *   <tr><td>尝试次数上限</td><td>6 位数字只有 100 万种，无限制可爆破</td>
 *       <td>拿着自己的邮箱就能猜出别人的验证码</td></tr>
 *   <tr><td>有效期</td><td>验证码泄漏后被长期利用</td>
 *       <td>邮件转发、截图外流都会变成长期后门</td></tr>
 * </table>
 *
 * <h2>验证码与场景绑定</h2>
 *
 * <p>键里带 {@code scene}，所以注册流程拿到的验证码**不能**用于重置密码 —— 两条链路的风险等级不同，见 {@link EmailCodeScene}。
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String KEY_CODE = "auth:code:";
    private static final String KEY_COOLDOWN = "auth:code:cooldown:";
    private static final String KEY_ATTEMPTS = "auth:code:attempts:";
    private static final String KEY_RESERVATION = "auth:code:reservation:";

    private static final DefaultRedisScript<Long> RESERVE_SEND_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end; "
                            + "redis.call('SET', KEYS[1], '1', 'PX', ARGV[2]); "
                            + "redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[3]); "
                            + "redis.call('DEL', KEYS[3]); return 1;",
                    Long.class);

    private static final DefaultRedisScript<Long> RESERVE_VERIFY_SCRIPT =
            new DefaultRedisScript<>(
                    "local code=redis.call('GET', KEYS[1]); "
                            + "if not code then return -1 end; "
                            + "if redis.call('EXISTS', KEYS[3]) == 1 then return -1 end; "
                            + "local attempts=tonumber(redis.call('GET', KEYS[2]) or '0'); "
                            + "local max=tonumber(ARGV[2]); if attempts >= max then return -2 end; "
                            + "if code ~= ARGV[1] then "
                            + "local count=redis.call('INCR', KEYS[2]); "
                            + "if count == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[3]); end; "
                            + "if count >= max then return -2 end; return 0; end; "
                            + "redis.call('SET', KEYS[3], ARGV[4], 'PX', ARGV[3]); return 1;",
                    Long.class);

    private static final DefaultRedisScript<Long> FINISH_VERIFY_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[3]) ~= ARGV[1] then return 0 end; "
                            + "if ARGV[2] == 'commit' then redis.call('DEL', KEYS[1], KEYS[2]); end; "
                            + "redis.call('DEL', KEYS[3]); return 1;",
                    Long.class);

    /** 6 位数字：够用（配合尝试次数上限）且便于从邮件里照着敲。 */
    private static final int CODE_MODULUS = 1_000_000;

    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final UserService userService;
    private final MailSender mailSender;
    private final SystemConfigService systemConfigService;

    public EmailCodeService(
            StringRedisTemplate redis,
            AuthProperties properties,
            UserService userService,
            MailSender mailSender,
            SystemConfigService systemConfigService) {
        this.redis = redis;
        this.properties = properties;
        this.userService = userService;
        this.mailSender = mailSender;
        this.systemConfigService = systemConfigService;
    }

    /**
     * 生成验证码并发送。
     *
     * <p>发信是**异步且失败不回滚**的（D56）：这里只负责把验证码写进 Redis 并交给 {@link MailSender}，SMTP 抖动不会让本次请求失败。用户没收到就重发
     * —— 把「发信失败」和「业务失败」绑在一起，结果只会是「邮件服务一挂，谁也别想注册」。
     *
     * @throws BusinessException 冷却中（{@link ErrorCode#EMAIL_CODE_TOO_FREQUENT}）、 或注册场景下邮箱已被占用（{@link
     *     ErrorCode#EMAIL_EXISTS}）
     */
    public void send(EmailCodeScene scene, String email) {
        systemConfigService.requireEnabled(
                SystemConfigService.AUTH_EMAIL_CODE_ENABLED, ErrorCode.EMAIL_CODE_DISABLED);
        if (scene == EmailCodeScene.REGISTER) {
            systemConfigService.requireEnabled(
                    SystemConfigService.AUTH_REGISTER_ENABLED, ErrorCode.REGISTRATION_DISABLED);
        } else if (scene == EmailCodeScene.RESET_PASSWORD) {
            systemConfigService.requireEnabled(
                    SystemConfigService.AUTH_PASSWORD_RESET_ENABLED,
                    ErrorCode.PASSWORD_RESET_DISABLED);
        }
        email = UserService.normalizeEmail(email);
        if (!sceneAllowsSending(scene, email)) {
            // 防枚举：重置密码场景下邮箱未注册时，对外与成功完全一致（连冷却都不占用，
            // 否则「第一次成功、第二次报冷却」本身又成了一个可观测的差异）
            return;
        }

        String scope = scope(scene, email);
        String cooldownKey = KEY_COOLDOWN + scope;
        String code = "%06d".formatted(RANDOM.nextInt(CODE_MODULUS));
        Long reserved =
                redis.execute(
                        RESERVE_SEND_SCRIPT,
                        List.of(cooldownKey, KEY_CODE + scope, KEY_ATTEMPTS + scope),
                        code,
                        String.valueOf(properties.emailCodeCooldown().toMillis()),
                        String.valueOf(properties.emailCodeTtl().toMillis()));
        if (!Long.valueOf(1L).equals(reserved)) {
            throw new BusinessException(
                    ErrorCode.EMAIL_CODE_TOO_FREQUENT,
                    "验证码已发送，请 %d 秒后再试".formatted(properties.emailCodeCooldown().toSeconds()));
        }

        mailSender.sendText(scene.name(), email, subject(scene), body(scene, code));
    }

    /**
     * 校验并占用验证码。数据库事务提交后销毁；回滚时释放占用，允许用户重试。
     *
     * @throws BusinessException 验证码错误 / 已过期 / 尝试次数超限
     */
    public void verify(EmailCodeScene scene, String email, String code) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("验证码校验必须在数据库事务中执行");
        }
        email = UserService.normalizeEmail(email);
        String scope = scope(scene, email);
        String codeKey = KEY_CODE + scope;
        String attemptsKey = KEY_ATTEMPTS + scope;
        String reservationKey = KEY_RESERVATION + scope;
        String reservationId = UUID.randomUUID().toString();
        Long result =
                redis.execute(
                        RESERVE_VERIFY_SCRIPT,
                        List.of(codeKey, attemptsKey, reservationKey),
                        code,
                        String.valueOf(properties.emailCodeMaxAttempts()),
                        String.valueOf(properties.emailCodeTtl().toMillis()),
                        reservationId);
        if (Long.valueOf(1L).equals(result)) {
            registerReservationCompletion(codeKey, attemptsKey, reservationKey, reservationId);
            return;
        }
        if (Long.valueOf(-2L).equals(result)) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED);
        }
        throw new BusinessException(ErrorCode.EMAIL_CODE_INVALID);
    }

    private void registerReservationCompletion(
            String codeKey, String attemptsKey, String reservationKey, String reservationId) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        redis.execute(
                                FINISH_VERIFY_SCRIPT,
                                List.of(codeKey, attemptsKey, reservationKey),
                                reservationId,
                                status == STATUS_COMMITTED ? "commit" : "rollback");
                    }
                });
    }

    /**
     * 场景策略：允许发信返回 {@code true}，静默跳过返回 {@code false}。
     *
     * <p>两个场景在这里刻意不对称，理由见 {@link EmailCodeScene} 的表格。
     */
    private boolean sceneAllowsSending(EmailCodeScene scene, String email) {
        return switch (scene) {
            case REGISTER -> {
                // 注册接口本来就要告诉用户「这个邮箱用过了」，验证码接口瞒着没有意义，
                // 反而让用户先收到一封注定用不上的邮件
                if (userService.existsByEmail(email)) {
                    throw new BusinessException(ErrorCode.EMAIL_EXISTS);
                }
                yield true;
            }
            case RESET_PASSWORD -> {
                if (userService.findByEmail(email).isEmpty()) {
                    log.info("重置密码验证码请求的邮箱未注册，按防枚举策略静默跳过发信");
                    yield false;
                }
                yield true;
            }
        };
    }

    private static String scope(EmailCodeScene scene, String email) {
        return scene.name() + ":" + email;
    }

    private static String subject(EmailCodeScene scene) {
        return "【LoomBot】%s验证码".formatted(scene.label());
    }

    private String body(EmailCodeScene scene, String code) {
        return """
                你正在%s，验证码是：

                    %s

                验证码 %d 分钟内有效，请勿转发给任何人。
                如果不是你本人操作，忽略本邮件即可。
                """
                .formatted(scene.label(), code, properties.emailCodeTtl().toMinutes());
    }
}
