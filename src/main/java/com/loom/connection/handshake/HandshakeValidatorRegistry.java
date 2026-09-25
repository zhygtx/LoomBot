package com.loom.connection.handshake;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 握手校验器注册表。
 *
 * <h2>失败时必须关闭（fail closed）</h2>
 *
 * <p>遇到**未知 mode** 一律拒绝，绝不放行。理由很直白：插件声明了一个 Java 不认识的校验方式， 意味着我们不知道该怎么校验它 ——
 * 此时「放行」等于「任何知道路径的人都能连进来」。 宁可连不上并报明确错误，也不能静默失效。
 */
public final class HandshakeValidatorRegistry {

    private static final Logger log = LoggerFactory.getLogger(HandshakeValidatorRegistry.class);

    private final Map<String, HandshakeValidator> validators = new HashMap<>();

    public HandshakeValidatorRegistry() {
        this(BuiltinHandshakeValidators.all());
    }

    public HandshakeValidatorRegistry(List<HandshakeValidator> custom) {
        for (HandshakeValidator validator : custom) {
            HandshakeValidator previous = validators.put(validator.mode(), validator);
            if (previous != null) {
                log.warn("握手校验器 mode 冲突，后者覆盖前者: {}", validator.mode());
            }
        }
    }

    /**
     * 校验握手。
     *
     * @param secret 从 config 按 {@code secretField} 取出的密钥值
     * @return {@code null} 表示通过；否则是拒绝原因
     */
    public String validate(HandshakeSpec spec, String secret, HandshakeRequest request) {
        if (spec == null) {
            return "未声明握手校验规格";
        }
        HandshakeValidator validator = validators.get(spec.mode());
        if (validator == null) {
            // custom 是逃生通道：需要回调适配器。当前未实现 → 拒绝而不是放行。
            if (HandshakeSpec.MODE_CUSTOM.equals(spec.mode())) {
                return "custom 握手校验尚未实现，请改用内置模式或先实现回调";
            }
            return "未知的握手校验模式: " + spec.mode();
        }
        return validator.failureReason(spec, secret, request);
    }

    // TODO(auth): 实现 x-handshake.mode = "custom" 的适配器回调。
    //
    //   改动清单：
    //     1. AdapterSession 加 request("handshake.check", {headers, query}, timeout)
    //     2. 本类在 mode=custom 时调用它。注意避开 manager → handshake → adapter 的循环依赖：
    //        注册表不能直接 new AdapterSession，得由 ConnectionConfig 注入一个
    //        Supplier<AdapterSession>（或窄接口 HandshakeDelegate）
    //     3. 适配器不在线时仍须 fail closed —— 这条现在是自动成立的，别改坏
    //
    //   为什么先做内置校验器、而不是直接上回调：
    //     内置的 none / bearerToken / queryParam / hmacSha256 覆盖了四种常见形态，
    //     而回调要在握手路径上引入一次 IPC。两者不是替代关系 ——
    //     复杂平台（OAuth2 验签、JWT claims、密钥嵌在 path 里）走 custom 完全合理。
    //
    //   ⚠️ 早期文档曾以「IPC 在热路径 + 适配器挂了连不进来」为由否定这个特性，
    //   那两个理由都不成立（握手是每连接一次；适配器不在线时反向连接本来也没用），
    //   已在 docs/适配器规范.md 里更正。

    public boolean supports(String mode) {
        return validators.containsKey(mode);
    }
}
