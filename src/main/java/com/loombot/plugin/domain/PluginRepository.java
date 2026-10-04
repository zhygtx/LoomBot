package com.loombot.plugin.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 插件库的同步状态。
 *
 * <p>声明（仓库地址、分支、用哪个子扫描器）在插件库文件夹的 `repo.json` 里，不在这张表里—— 文件夹才是"有哪些库"的真相，表只记"上次同步到哪了"。
 */
@TableName("plugin_repository")
@Getter
@Setter
public class PluginRepository {

    @TableId private Long id;

    private String repoKey;

    /** 这次同步时工作副本在哪；纯本地库也记，出问题好定位。 */
    private String localPath;

    private String lastCommitHash;
    private LocalDateTime lastPullTime;
    private LocalDateTime lastScanTime;

    /**
     * 上次同步失败的原因。成功同步后必须能清空，所以显式声明 {@code ALWAYS}。
     *
     * <p>MyBatis-Plus 默认的 {@code NOT_NULL} 策略会忽略 null 字段：{@code setLastError(null)} 配 {@code
     * updateById} 根本写不进库。后果是失败一次之后这条错误永远留在列表里，而且「内容未变化就跳过扫描」的判据也用到 {@code lastError ==
     * null}，会跟着一直失效。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
