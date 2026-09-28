package com.loom.plugin.sync;

import com.loom.config.AdapterProperties;
import com.loom.plugin.PluginProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 为带 requirements.txt 的插件版本创建私有 venv。
 *
 * <p>没有 requirements.txt 的插件不创建 venv，继续使用宿主配置的 Python。版本目录不可变， 所以 venv 一旦存在就可以直接复用。
 */
@Component
public class PluginDependencyInstaller {

    private static final Logger log = LoggerFactory.getLogger(PluginDependencyInstaller.class);

    private final AdapterProperties adapterProperties;
    private final PluginProperties pluginProperties;

    public PluginDependencyInstaller(
            AdapterProperties adapterProperties, PluginProperties pluginProperties) {
        this.adapterProperties = adapterProperties;
        this.pluginProperties = pluginProperties;
    }

    /**
     * @return 该版本应使用的 Python 路径；没有依赖时返回 {@code null}，表示使用宿主 Python。
     */
    public String ensure(Path pluginDir) throws IOException, InterruptedException {
        Path requirements = pluginDir.resolve("requirements.txt");
        if (!Files.isRegularFile(requirements)) {
            return null;
        }
        Path venv = pluginDir.resolve(".venv");
        Path python = venvPython(venv);
        if (!Files.isRegularFile(python)) {
            run(List.of(adapterProperties.pythonCommand(), "-m", "venv", venv.toString()), 120);
        }
        run(
                List.of(
                        python.toString(),
                        "-m",
                        "pip",
                        "install",
                        "--disable-pip-version-check",
                        "-r",
                        requirements.toString()),
                pluginProperties.dependencyInstallTimeoutSeconds());
        log.info("插件依赖已安装: pluginDir={}, python={}", pluginDir, python);
        return python.toString();
    }

    private static Path venvPython(Path venv) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? venv.resolve("Scripts").resolve("python.exe")
                : venv.resolve("bin").resolve("python");
    }

    private static void run(List<String> command, int timeoutSeconds)
            throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .directory(Path.of(".").toAbsolutePath().normalize().toFile())
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("命令超时: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException(
                    "命令失败("
                            + process.exitValue()
                            + "): "
                            + String.join(" ", command)
                            + System.lineSeparator()
                            + output);
        }
    }
}
