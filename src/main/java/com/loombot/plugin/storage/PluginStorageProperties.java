package com.loombot.plugin.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 插件持久化配置。
 *
 * <p>三段配置各自对应一层：{@code control-token} 是内部接口的共享令牌，{@code internal-base-url} 是 Python 运行时回调 Java
 * 的地址，{@code object-root} 是对象存储的落地位置。
 *
 * <p>当前对象存储是本地目录实现。换成 R2/S3 时只需要替换 {@code ObjectStorage} 的实现， 表结构与插件 API 都不动 —— 所以这里保留 {@code
 * object-root} 这种"后端参数"， 而不是把它写死在代码里。
 *
 * <pre>{@code
 * loombot:
 *   plugin-storage:
 *     control-token: ${PLUGIN_STORAGE_CONTROL_TOKEN:...}
 *     internal-base-url: ${PLUGIN_STORAGE_BASE_URL:http://127.0.0.1:8080/internal/plugin-storage}
 *     object-root: ${PLUGIN_OBJECT_ROOT:python/plugin-objects}
 * }</pre>
 *
 * @param controlToken 内部接口令牌；Python 运行时用 {@code X-Plugin-Storage-Token} 携带
 * @param internalBaseUrl Java 内部存储接口的绝对地址，会通过环境变量下发给运行时
 * @param objectRoot 对象存储根目录；本地实现下相对路径按 Java 进程工作目录解析
 * @param maxFileBytes 单个文件上限，超过直接拒绝
 */
@ConfigurationProperties(prefix = "loombot.plugin-storage")
public record PluginStorageProperties(
        String controlToken, String internalBaseUrl, String objectRoot, Long maxFileBytes) {

    public PluginStorageProperties {
        controlToken =
                controlToken == null || controlToken.isBlank()
                        ? "loombot-dev-plugin-storage-token"
                        : controlToken.strip();
        internalBaseUrl =
                internalBaseUrl == null || internalBaseUrl.isBlank()
                        ? "http://127.0.0.1:8080/internal/plugin-storage"
                        : stripTrailingSlash(internalBaseUrl.strip());
        objectRoot =
                objectRoot == null || objectRoot.isBlank()
                        ? "python/plugin-objects"
                        : objectRoot.strip();
        maxFileBytes = maxFileBytes == null || maxFileBytes <= 0 ? 32L * 1024 * 1024 : maxFileBytes;
    }

    /** 对象存储根目录的绝对路径。 */
    public Path objectRootPath() {
        return Path.of(objectRoot).toAbsolutePath().normalize();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
