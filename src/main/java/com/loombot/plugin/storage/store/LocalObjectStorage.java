package com.loombot.plugin.storage.store;

import com.loombot.plugin.storage.PluginStorageProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 本地目录实现的对象存储。
 *
 * <p>先用它跑通整条链路：{@code ctx.storage.files} 的语义、元数据表、过期清理都不依赖远端服务， 部署时不用先申请 R2/S3 凭证。之后要换成云端，只需要再写一个
 * {@code ObjectStorage} 实现并让它优先装配， 上层代码零改动。
 *
 * <p>写文件走"临时文件 + 原子 rename"：进程在写到一半时被杀，留下的要么是旧内容、要么是新内容， 不会出现半截文件被后续读取当成有效数据。
 */
@Component
public class LocalObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalObjectStorage.class);

    private final Path root;

    public LocalObjectStorage(PluginStorageProperties properties) {
        this.root = properties.objectRootPath();
        log.info("插件对象存储使用本地目录: {}", root);
    }

    @Override
    public String backend() {
        return "local";
    }

    @Override
    public void put(String objectKey, byte[] data, String contentType) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Path temp =
                    target.resolveSibling(
                            "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.write(temp, data);
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入对象失败: " + objectKey, e);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        Path target = resolve(objectKey);
        if (!Files.isRegularFile(target)) {
            throw new ObjectNotFoundException("对象不存在: " + objectKey);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("读取对象失败: " + objectKey, e);
        }
    }

    @Override
    public boolean delete(String objectKey) {
        Path target = resolve(objectKey);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("删除对象失败: " + objectKey, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        String text = normalize(prefix);
        Path start = text.isEmpty() ? root : resolve(text);
        if (!Files.isDirectory(start)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(start)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列举对象失败: " + prefix, e);
        }
    }

    /**
     * 把对象键解析成根目录下的绝对路径。
     *
     * <p>解析后必须仍然落在根目录内：对象键来自插件，同进程内的其他插件不能靠 {@code ../} 越界读写宿主 任意文件。这道校验在本地实现里是安全边界，在远端实现里由 bucket
     * 天然提供。
     */
    private Path resolve(String objectKey) {
        String text = normalize(objectKey);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        Path target = root.resolve(text).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("对象键越界: " + objectKey);
        }
        return target;
    }

    private static String normalize(String objectKey) {
        String text = objectKey == null ? "" : objectKey.replace('\\', '/').strip();
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        return text;
    }
}
