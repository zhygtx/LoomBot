package com.loom.connection.dto;

import com.loom.connection.domain.ConnectionStatus;
import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;

/**
 * 连接的读模型。
 *
 * <h2>配置与运行时状态分开表达</h2>
 *
 * <p>{@code enabled} 是**人的意图**（数据库字段），{@code status.state} 是**此刻的事实**
 * （内存状态机）。两者经常不一致，而且不一致本身就是最有价值的信息：
 *
 * <ul>
 *   <li>{@code enabled=true} + {@code state=ONLINE} —— 正常
 *   <li>{@code enabled=true} + {@code state=RECONNECTING} —— 配错了地址或平台挂了
 *   <li>{@code enabled=true} + {@code state=WAITING_ADAPTER} —— 插件没起来
 * </ul>
 *
 * <p>如果把两者合并成一个「状态」字段，就再也表达不出「我想让它开着但现在连不上」。
 *
 * @param config 密钥字段已用 {@code ********} 掩码；回传时 Service 会还原。**是 JSON 对象**， 与请求侧形状一致 —— 客户端不需要做「字符串 ↔
 *     对象」的转换
 * @param endpointPath 反向连接的接入路径，正向为 {@code null}
 * @param typeReady 该连接类型当前是否有适配器在线；{@code false} 时前端应提示「插件未就绪」
 */
public record ConnectionResponse(
        Long id,
        String name,
        String connectionType,
        JsonNode config,
        String endpointPath,
        Long ownerUserId,
        boolean enabled,
        String remark,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        boolean typeReady,
        ConnectionStatus status) {}
