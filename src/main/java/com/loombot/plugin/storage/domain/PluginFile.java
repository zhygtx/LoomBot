package com.loombot.plugin.storage.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 一行插件文件元数据。文件内容不在这里，只有指向对象存储的键。 */
@Getter
@Setter
public class PluginFile {

    private Long id;
    private String pluginKey;
    private String scope;
    private String scopeId;

    /** 插件侧看到的逻辑引用，例如 {@code downloads/2026-10-05.bin}。 */
    private String fileRef;

    /** 对象存储里的键；本地实现下是根目录内的相对路径。 */
    private String objectKey;

    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private LocalDateTime createTime;
    private LocalDateTime updatedAt;
}
