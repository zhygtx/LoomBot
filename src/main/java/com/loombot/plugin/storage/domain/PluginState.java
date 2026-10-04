package com.loombot.plugin.storage.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 一行插件结构化状态。
 *
 * <p>没有代理主键：{@code (plugin_key, scope, scope_id, state_key)} 本身就是唯一的业务键， 再挂一个自增 id
 * 只会多一层需要维护的映射。读写都用它做等值条件，走的是主键索引。
 */
@Getter
@Setter
public class PluginState {

    private String pluginKey;
    private String scope;
    private String scopeId;
    private String stateKey;

    /** 值的 JSON 文本。写库时由 MySQL 解析成 JSON，读出来仍是 JSON 文本。 */
    private String valueJson;

    /** 乐观锁版本号；每次成功写入 +1。 */
    private Long valueVersion;

    /** 过期时间；NULL 表示永不过期。 */
    private LocalDateTime expiresAt;

    private LocalDateTime updatedAt;
}
