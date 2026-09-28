package com.loom.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loom.plugin.domain.PluginNode;
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
                (id, plugin_version_id, node_key, node_type, name, description,
                 input_schema, output_schema, source_ref, sort)
            VALUES
            <foreach collection="nodes" item="item" separator=",">
                (#{item.id}, #{item.pluginVersionId}, #{item.nodeKey}, #{item.nodeType},
                 #{item.name}, #{item.description}, #{item.inputSchema}, #{item.outputSchema},
                 #{item.sourceRef}, #{item.sort})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("nodes") List<PluginNode> nodes);
}
