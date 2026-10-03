package com.loombot.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loombot.plugin.domain.PluginVersion;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PluginVersionMapper extends BaseMapper<PluginVersion> {

    @Insert(
            """
            <script>
            INSERT INTO plugin_version
                (id, plugin_id, version, source_commit_hash, manifest_json,
                 manifest_sha256, artifact_sha256, install_path, entry_point,
                 python_path, runtime_key, published_time, synced_time)
            VALUES
            <foreach collection="versions" item="item" separator=",">
                (#{item.id}, #{item.pluginId}, #{item.version}, #{item.sourceCommitHash},
                 #{item.manifestJson}, #{item.manifestSha256}, #{item.artifactSha256},
                 #{item.installPath}, #{item.entryPoint}, #{item.pythonPath},
                 #{item.runtimeKey}, #{item.publishedTime}, #{item.syncedTime})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("versions") List<PluginVersion> versions);
}
