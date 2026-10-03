package com.loombot.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loombot.plugin.domain.PluginConnectionType;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PluginConnectionTypeMapper extends BaseMapper<PluginConnectionType> {

    @Insert(
            """
            <script>
            INSERT INTO plugin_connection_type
                (id, plugin_version_id, connection_type, entry_point, display_name, direction,
                 protocol_version, schema_version, config_schema, config_schema_sha256,
                 sort)
            VALUES
            <foreach collection="types" item="item" separator=",">
                (#{item.id}, #{item.pluginVersionId}, #{item.connectionType}, #{item.entryPoint},
                 #{item.displayName}, #{item.direction}, #{item.protocolVersion},
                 #{item.schemaVersion}, #{item.configSchema}, #{item.configSchemaSha256},
                 #{item.sort})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("types") List<PluginConnectionType> types);
}
