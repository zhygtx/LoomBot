package com.loombot.auth.controller;

import com.loombot.auth.dto.EmailCodeRequest;
import com.loombot.auth.dto.LoginRequest;
import com.loombot.auth.dto.LoginResponse;
import com.loombot.auth.dto.RegisterRequest;
import com.loombot.auth.dto.ResetPasswordRequest;
import com.loombot.auth.dto.UserProfileResponse;
import com.loombot.auth.security.BearerTokens;
import com.loombot.auth.service.AuthService;
import com.loombot.auth.service.EmailCodeService;
import com.loombot.common.api.ErrorCode;
import com.loombot.common.api.Result;
import com.loombot.common.exception.BusinessException;
import com.loombot.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册、登录、找回密码、登出。
 *
 * <h2>路径的划分依据是「要不要带令牌」，不是「归哪个模块」</h2>
 *
 * <ul>
 *   <li>{@code /email-code}、{@code /register}、{@code /login}、{@code /password/reset} ——
 *       <b>必须匿名可访问</b>。这是它们存在的意义：用户此刻还没有令牌。 这四个在 {@code SecurityConfig} 里显式放行。
 *   <li>{@code /logout}、{@code /me} —— 需要认证。
 * </ul>
 *
 * <h2>为什么全部用 POST</h2>
 *
 * <p>登录与重置密码都在**创建**一份凭证（令牌 / 新密码），不是查询。虽然语义上可以 塞进查询串，但那样密码会进 URL —— URL 会被记进访问日志、浏览器历史、Referer 头。
 * 这条不是风格问题。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** {@code sys_user.last_login_ip} 的列宽，超长会直接插入失败。 */
    private static final int MAX_IP_LENGTH = 64;

    private final AuthService authService;
    private final EmailCodeService emailCodeService;

    public AuthController(AuthService authService, EmailCodeService emailCodeService) {
        this.authService = authService;
        this.emailCodeService = emailCodeService;
    }

    /**
     * 发送邮箱验证码。
     *
     * <p>重置密码场景下，邮箱未注册时**同样返回成功**（只是不发信）—— 见 {@code EmailCodeService} 的防枚举说明。所以前端不能靠这个接口判断邮箱是否存在。
     */
    @PostMapping("/email-code")
    public Result<Void> sendEmailCode(@Valid @RequestBody EmailCodeRequest request) {
        emailCodeService.send(request.scene(), request.email());
        return Result.success();
    }

    /**
     * 注册。
     *
     * <p>不自动登录：本接口只创建用户，令牌由 {@code /login} 签发。这样两条路径的失败模式互不缠绕。
     *
     * @return 新用户 ID
     */
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    /**
     * 登录，签发令牌。
     *
     * <p>失败了只有两种可能：{@link ErrorCode#LOGIN_FAILED}（邮箱或密码错）与 {@link
     * ErrorCode#ACCOUNT_DISABLED}（账号停用）。「邮箱不存在」不会单独出现。
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return Result.success(authService.login(request, clientIp(httpRequest)));
    }

    /** 重置密码（忘记密码）。成功后该用户的所有令牌立即失效。 */
    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return Result.success();
    }

    /**
     * 登出。
     *
     * <p>幂等：令牌已失效也返回成功。需要认证这一点看起来有点绕（毕竟要带着令牌来登出）， 但那正是「删掉这个令牌」的前提条件。
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout(BearerTokens.resolve(request));
        return Result.success();
    }

    /**
     * 当前登录用户信息，含角色与权限串。
     *
     * <p>前端刷新页面后靠它恢复「我是谁、我能点哪些按钮」。取的是数据库里的最新值，所以邮箱或权限变化会立刻反映。
     *
     * <p>用户不存在时返回 401 而不是 404：能走到这里说明令牌是有效的， 而用户没了意味着「这个登录态已经不该存在」—— 前端该做的是清掉本地状态回登录页， 这与收到 404
     * 时的处置完全不同。
     */
    @GetMapping("/me")
    public Result<UserProfileResponse> me() {
        Long userId =
                CurrentUser.id().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return Result.success(authService.currentProfile(userId));
    }

    /**
     * 取客户端 IP，仅用于记录「最后登录 IP」。
     *
     * <p>优先取 {@code X-Forwarded-For} 的第一段：反向代理后面 {@code getRemoteAddr()}
     * 永远是代理自己的地址，记下来毫无信息量。这个值**可以被客户端伪造**，所以它只做展示， 不能拿来做任何安全判断 —— 真要按 IP 限流得靠代理层或专门的方案。
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip =
                forwarded == null || forwarded.isBlank()
                        ? request.getRemoteAddr()
                        : forwarded.split(",")[0].trim();
        if (ip == null || ip.isBlank()) {
            return null;
        }
        return ip.length() > MAX_IP_LENGTH ? ip.substring(0, MAX_IP_LENGTH) : ip;
    }
}
