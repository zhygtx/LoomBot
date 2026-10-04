package com.loombot.plugin.storage.mapper;

import com.loombot.plugin.storage.domain.PluginState;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 插件结构化状态读写。SQL 全部显式写出，因为主键是业务组合键，没有代理 id 可用。 */
@Mapper
public interface PluginStateMapper {

    /**
     * 加行锁读取，用于"读版本 → 比对 → 写入"的 CAS。
     *
     * <p>行不存在时 InnoDB 会在唯一索引上加间隙锁，因此两个并发的新建请求会串行化， 不会同时以为自己拿到了 {@code expected_version=0}。
     */
    @Select(
            """
            SELECT plugin_key, scope, scope_id, state_key, value_json, value_version, expires_at
            FROM plugin_state
            WHERE plugin_key = #{pluginKey} AND scope = #{scope}
              AND scope_id = #{scopeId} AND state_key = #{stateKey}
            FOR UPDATE
            """)
    PluginState selectForUpdate(
            @Param("pluginKey") String pluginKey,
            @Param("scope") String scope,
            @Param("scopeId") String scopeId,
            @Param("stateKey") String stateKey);

    @Select(
            """
            SELECT plugin_key, scope, scope_id, state_key, value_json, value_version, expires_at
            FROM plugin_state
            WHERE plugin_key = #{pluginKey} AND scope = #{scope}
              AND scope_id = #{scopeId} AND state_key = #{stateKey}
            """)
    PluginState selectOne(
            @Param("pluginKey") String pluginKey,
            @Param("scope") String scope,
            @Param("scopeId") String scopeId,
            @Param("stateKey") String stateKey);

    @Insert(
            """
            INSERT INTO plugin_state
                (plugin_key, scope, scope_id, state_key, value_json, value_version, expires_at)
            VALUES
                (#{pluginKey}, #{scope}, #{scopeId}, #{stateKey},
                 #{valueJson}, #{valueVersion}, #{expiresAt})
            """)
    int insert(PluginState state);

    @Update(
            """
            UPDATE plugin_state
            SET value_json = #{valueJson},
                value_version = #{valueVersion},
                expires_at = #{expiresAt}
            WHERE plugin_key = #{pluginKey} AND scope = #{scope}
              AND scope_id = #{scopeId} AND state_key = #{stateKey}
            """)
    int update(PluginState state);

    @Delete(
            """
            DELETE FROM plugin_state
            WHERE plugin_key = #{pluginKey} AND scope = #{scope}
              AND scope_id = #{scopeId} AND state_key = #{stateKey}
            """)
    int deleteOne(
            @Param("pluginKey") String pluginKey,
            @Param("scope") String scope,
            @Param("scopeId") String scopeId,
            @Param("stateKey") String stateKey);

    /**
     * 按前缀扫描。
     *
     * <p>{@code pattern} 由 {@code PluginStorageService} 拼好并转义，SQL 里不再做字符串拼接。 转义是必要的：key
     * 本身允许下划线，而下划线在 {@code LIKE} 里是"任意单字符"， 不转义的话 {@code keys("user_")} 会连 {@code userX} 一起匹配出来。
     */
    @Select(
            """
            SELECT plugin_key, scope, scope_id, state_key, value_json, value_version, expires_at
            FROM plugin_state
            WHERE plugin_key = #{pluginKey} AND scope = #{scope} AND scope_id = #{scopeId}
              AND state_key LIKE #{pattern} ESCAPE '!'
            ORDER BY state_key
            LIMIT #{limit}
            """)
    List<PluginState> selectByPrefix(
            @Param("pluginKey") String pluginKey,
            @Param("scope") String scope,
            @Param("scopeId") String scopeId,
            @Param("pattern") String pattern,
            @Param("limit") int limit);

    /** 清理过期行。用 Java 传入的"现在"，保证与写入时的时钟一致。 */
    @Delete("DELETE FROM plugin_state WHERE expires_at IS NOT NULL AND expires_at <= #{now}")
    int purgeExpired(@Param("now") LocalDateTime now);
}
