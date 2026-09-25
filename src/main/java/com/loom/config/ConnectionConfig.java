package com.loom.config;

import com.loom.connection.handshake.BuiltinHandshakeValidators;
import com.loom.connection.handshake.HandshakeValidator;
import com.loom.connection.handshake.HandshakeValidatorRegistry;
import com.loom.runtime.ipc.IpcCodec;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接模块的装配。
 *
 * <p>{@link HandshakeValidatorRegistry} 与 {@link IpcCodec} 都是不可变的值对象， 不适合打
 * {@code @Component}（构造参数是「能力」而不是注入的「依赖」）， 所以在这里显式声明。
 *
 * <h2>为什么这里要手动拼内置校验器</h2>
 *
 * <p>{@code HandshakeValidatorRegistry(List)} 这个构造器**接收的是完整集合**， 并不是「额外的自定义项」—— 无参构造器也只是把它当 {@code
 * BuiltinHandshakeValidators.all()} 来调。所以如果这里直接注入自定义 Bean 列表传进去，四个内置校验器会全部消失，
 * 结果是**所有握手都被判定为「未知模式」而拒绝**，且现象极难定位。 因此必须显式合并。
 */
@Configuration
public class ConnectionConfig {

    private static final Logger log = LoggerFactory.getLogger(ConnectionConfig.class);

    @Bean
    public IpcCodec ipcCodec(ObjectMapper objectMapper) {
        return new IpcCodec(objectMapper);
    }

    @Bean
    public HandshakeValidatorRegistry handshakeValidatorRegistry(
            List<HandshakeValidator> customValidators) {
        List<HandshakeValidator> builtins = BuiltinHandshakeValidators.all();
        // 注册表对 mode 冲突是「后者覆盖前者」。如果放任自定义项覆盖内置，
        // 一个恰好叫 "none" 的自定义校验器就能静默关掉全部握手校验 —— 这是安全漏洞而非特性。
        // 所以这里主动剔除与内置同名的自定义项，并告警。
        List<HandshakeValidator> accepted = new ArrayList<>(builtins);
        for (HandshakeValidator candidate : customValidators) {
            boolean clash =
                    builtins.stream().anyMatch(builtin -> builtin.mode().equals(candidate.mode()));
            if (clash) {
                log.error("握手校验器 {} 与内置实现同名，已忽略自定义版本（内置不可覆盖）", candidate.mode());
                continue;
            }
            accepted.add(candidate);
        }
        return new HandshakeValidatorRegistry(accepted);
    }
}
