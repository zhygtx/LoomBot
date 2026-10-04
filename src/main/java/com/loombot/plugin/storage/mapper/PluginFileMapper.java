package com.loombot.plugin.storage.mapper;

import com.loombot.plugin.storage.domain.PluginFile;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 插件文件元数据读写。文件内容一律走 {@code ObjectStorage}。 */
@Mapper
public interface PluginFileMapper {

    @Select(
            """
            SELECT id, plugin_key, scope, scope_id, file_ref, object_key,
                   content_type, size_bytes, sha256, create_time, updated_at
            FROM plugin_file
            WHERE plugin_key = #{pluginKey} AND scope = #{scope}
              AND scope_id = #{scopeId} AND file_ref = #{fileRef}
            """)
    PluginFile selectOne(
            @Param("pluginKey") String pluginKey,
            @Param("scope") String scope,
            @Param("scopeId") String scopeId,
            @Param("fileRef") String fileRef);

    @Insert(
            """
            INSERT INTO plugin_file
                (id, plugin_key, scope, scope_id, file_ref, object_key,
                 content_type, size_bytes, sha256)
            VALUES
                (#{id}, #{pluginKey}, #{scope}, #{scopeId}, #{fileRef}, #{objectKey},
                 #{contentType}, #{sizeBytes}, #{sha256})
            """)
    int insert(PluginFile file);

    /** 只改"指向哪个对象"和它的描述信息；引用本身是等值条件，不允许被更新改写。 */
    @Update(
            """
            UPDATE plugin_file
            SET object_key = #{objectKey},
                content_type = #{contentType},
                size_bytes = #{sizeBytes},
                sha256 = #{sha256}
            WHERE id = #{id}
            """)
    int updateContent(PluginFile file);

    @Delete(
            """
            DELETE FROM plugin_file
            WHERE plugin_key = #{pluginKey} AND scope = #{scope}
              AND scope_id = #{scopeId} AND file_ref = #{fileRef}
            """)
    int deleteOne(
            @Param("pluginKey") String pluginKey,
            @Param("scope") String scope,
            @Param("scopeId") String scopeId,
            @Param("fileRef") String fileRef);

    @Select(
            """
            SELECT id, plugin_key, scope, scope_id, file_ref, object_key,
                   content_type, size_bytes, sha256, create_time, updated_at
            FROM plugin_file
            WHERE plugin_key = #{pluginKey} AND scope = #{scope} AND scope_id = #{scopeId}
              AND file_ref LIKE #{pattern} ESCAPE '!'
            ORDER BY file_ref
            LIMIT #{limit}
            """)
    List<PluginFile> selectByPrefix(
            @Param("pluginKey") String pluginKey,
            @Param("scope") String scope,
            @Param("scopeId") String scopeId,
            @Param("pattern") String pattern,
            @Param("limit") int limit);
}
