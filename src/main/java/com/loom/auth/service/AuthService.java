package com.loom.auth.service;

import com.loom.auth.domain.EmailCodeScene;
import com.loom.auth.domain.SysUser;
import com.loom.auth.dto.LoginRequest;
import com.loom.auth.dto.LoginResponse;
import com.loom.auth.dto.RegisterRequest;
import com.loom.auth.dto.ResetPasswordRequest;
import com.loom.auth.dto.UserProfileResponse;
import com.loom.common.api.ErrorCode;
import com.loom.common.exception.BusinessException;
import com.loom.system.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 认证流程的编排。
 *
 * <h2>为什么单独有这一层，而不是把流程写在 Controller 里</h2>
 *
 * <p>每个流程都要动两个以上的协作者（验证码 + 用户 + 令牌），而且**顺序本身是安全语义**： 是「先验密码再看账号是否停用」还是反过来，决定了攻击者能不能拿这个接口当
 * 「哪些邮箱被停用了」的探测器。这类顺序属于业务规则，不属于 HTTP 层 —— 放在 Controller 里，下一个人重排两行代码不会有任何提示。
 *
 * <p>本类刻意保持很薄：不碰数据库、不碰 Redis，只决定「先做什么、后做什么、失败怎么办」。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserService userService;
    private final EmailCodeService emailCodeService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final SystemConfigService systemConfigService;

    public AuthService(
            UserService userService,
            EmailCodeService emailCodeService,
            TokenService tokenService,
            PasswordEncoder passwordEncoder,
            SystemConfigService systemConfigService) {
        this.userService = userService;
        this.emailCodeService = emailCodeService;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.systemConfigService = systemConfigService;
    }

    /**
     * 注册。
     *
     * <p>顺序是「先验验证码，再落库」。反过来（先建账号再验码）会留下一个坏状态： 验证码错了但账号已经建出来了，而用户拿不到这个账号的密码 —— 一行脏数据换不来任何好处。
     *
     * @return 新用户 ID
     */
    @Transactional
    public Long register(RegisterRequest request) {
        systemConfigService.requireEnabled(
                SystemConfigService.AUTH_REGISTER_ENABLED, ErrorCode.REGISTRATION_DISABLED);
        emailCodeService.verify(EmailCodeScene.REGISTER, request.email(), request.code());
        SysUser user = userService.register(request.account(), request.email(), request.password());
        return user.getId();
    }

    /**
     * 登录。
     *
     * <p>三步的顺序不能换：
     *
     * <ol>
     *   <li><b>先找用户再验密码，且两者失败返回同一个错误码</b> —— 区分「邮箱不存在」与 「密码错误」等于免费提供「这个邮箱注册过没有」的查询接口
     *   <li><b>验完密码才检查是否停用</b> —— 否则任何人都能探测出哪些账号被停用了
     *   <li><b>最后才记登录时间</b> —— 这样返回给用户的「上次登录时间」是真正的上一次， 而不是他刚刚这一次。「我的账号上次什么时候登录的」正是用户用来发现
     *       异常登录的那个问题，答案要是刚才，这个字段就白留了
     * </ol>
     */
    public LoginResponse login(LoginRequest request, String clientIp) {
        systemConfigService.requireEnabled(
                SystemConfigService.AUTH_LOGIN_ENABLED, ErrorCode.LOGIN_DISABLED);
        SysUser user =
                userService
                        .findByEmail(request.email())
                        .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            log.info("登录失败（密码不匹配）: userId={}", user.getId());
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        if (!user.enabled()) {
            log.info("登录被拒（账号停用）: userId={}", user.getId());
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        UserProfileResponse profile = userService.profile(user);
        userService.recordLoginSuccess(user.getId(), clientIp);
        TokenService.IssuedToken issued = tokenService.issue(user);

        log.info("登录成功: userId={}, ip={}", user.getId(), clientIp);
        return new LoginResponse(issued.token(), "Bearer", issued.expiresInSeconds(), profile);
    }

    /**
     * 重置密码（忘记密码）。
     *
     * <p>用户不存在时返回的是 {@link ErrorCode#EMAIL_CODE_INVALID} —— 与「验证码错了」同一个
     * 错误码。因为验证码在「邮箱未注册」时根本没发出去，所以对调用方而言这两个原因 本来就不可能区分，也不该被区分：能区分就等于能枚举邮箱。
     *
     * <p>改完密码**吊销该用户全部令牌**。用户来重置密码，动机很可能是「怀疑密码泄漏了」， 此时把旧会话留着，等于把最需要赶走的人留在屋里。
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        systemConfigService.requireEnabled(
                SystemConfigService.AUTH_PASSWORD_RESET_ENABLED, ErrorCode.PASSWORD_RESET_DISABLED);
        SysUser user =
                userService
                        .findByEmail(request.email())
                        .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_CODE_INVALID));

        emailCodeService.verify(EmailCodeScene.RESET_PASSWORD, request.email(), request.code());
        userService.updatePassword(user.getId(), request.newPassword());
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        tokenService.revokeAll(user.getId());
                    }
                });
    }

    /** 登出：吊销当前令牌。幂等，令牌已失效时不报错。 */
    public void logout(String token) {
        tokenService.revoke(token);
    }

    /** 当前登录用户的信息。取的是**库里的最新值**，不是令牌里的快照。 */
    public UserProfileResponse currentProfile(Long userId) {
        SysUser user =
                userService
                        .findById(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return userService.profile(user);
    }
}
