package com.loombot.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loombot.plugin.domain.PluginNode;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PluginNodeMapper extends BaseMapper<PluginNode> {

    @Insert(
            """
            <script>
            INSERT INTO plugin_node
                (id, plugin_version_id, node_key, node_type, connection_type, name, description,
                 input_schema, output_schema, source_ref, signature_hash, sort)
            VALUES
            <foreach collection="nodes" item="item" separator=",">
                (#{item.id}, #{item.pluginVersionId}, #{item.nodeKey}, #{item.nodeType},
                 #{item.connectionType},
                 #{item.name}, #{item.description}, #{item.inputSchema}, #{item.outputSchema},
                 #{item.sourceRef}, #{item.signatureHash}, #{item.sort})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("nodes") List<PluginNode> nodes);
}
