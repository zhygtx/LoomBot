package com.loombot.auth.dto;

/**
 * 登录响应。
 *
 * <h2>为什么 {@code tokenType} 也要返回</h2>
 *
 * <p>前端拼请求头时需要知道前缀（{@code Authorization: Bearer <token>}）。把它写死在前端是能跑， 但它属于协议的一部分 ——
 * 由服务端声明、客户端照做，改的时候才不会漏改一处。
 *
 * @param token JWT
 * @param tokenType 固定 {@code Bearer}
 * @param expiresIn 有效秒数。前端据此决定要不要提前提示重新登录
 * @param user 当前用户信息，含权限串
 */
public record LoginResponse(
        String token, String tokenType, long expiresIn, UserProfileResponse user) {}
