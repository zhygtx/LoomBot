package com.loom.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loom.plugin.domain.PluginDependency;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PluginDependencyMapper extends BaseMapper<PluginDependency> {

    @Insert(
            """
            <script>
            INSERT INTO plugin_dependency
                (id, plugin_version_id, package_name, version_spec, resolved_version,
                 wheel_sha256, source_url, marker)
            VALUES
            <foreach collection="dependencies" item="item" separator=",">
                (#{item.id}, #{item.pluginVersionId}, #{item.packageName}, #{item.versionSpec},
                 #{item.resolvedVersion}, #{item.wheelSha256}, #{item.sourceUrl}, #{item.marker})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("dependencies") List<PluginDependency> dependencies);
}
