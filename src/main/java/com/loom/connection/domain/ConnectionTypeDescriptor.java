package com.loom.connection.domain;

import com.loom.connection.handshake.HandshakeSpec;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 连接类型描述 —— 由适配器在 {@code hello} 里声明，供前端渲染配置表单。
 *
 * <h2>为什么它不是持久化实体</h2>
 *
 * <p>连接类型的「真相」在插件里，不在数据库里。数据库只存某条连接用了哪个类型名 （{@code
 * ws_connection.connection_type}）。所以插件卸载/升级后，库里可能存在 「当前没有适配器声明的类型」的连接 —— 这不是数据损坏，运行时状态会是 {@code
 * WAITING_ADAPTER}。
 *
 * <p>{@link #ready} 表达的就是这件事：该类型此刻是否有适配器在线。
 *
 * @param type 类型标识，由适配器声明，创建连接时按它匹配
 * @param displayName 展示给用户的名称
 * @param direction 连接方向，决定接入地址归谁管、以及要由谁来发起
 * @param configSchema 配置表单 schema（JSON Schema + {@code x-} 扩展），Java 不解释其含义
 * @param handshakeSpec 反向连接的握手校验规格
 * @param capabilities 适配器声明的能力标记（如事件类型清单），供前端提示
 * @param adapterName 声明该类型的适配器进程名，排查时用
 * @param ready 该适配器当前是否已就绪
 */
public record ConnectionTypeDescriptor(
        String type,
        String displayName,
        Direction direction,
        JsonNode configSchema,
        HandshakeSpec handshakeSpec,
        List<String> capabilities,
        String adapterName,
        boolean ready) {}
