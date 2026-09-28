package com.loom.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loom.plugin.domain.PluginCapability;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PluginCapabilityMapper extends BaseMapper<PluginCapability> {

    @Insert(
            """
            <script>
            INSERT INTO plugin_capability
                (id, plugin_version_id, capability, kind, target, detail_json)
            VALUES
            <foreach collection="capabilities" item="item" separator=",">
                (#{item.id}, #{item.pluginVersionId}, #{item.capability}, #{item.kind},
                 #{item.target}, #{item.detailJson})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("capabilities") List<PluginCapability> capabilities);
}
