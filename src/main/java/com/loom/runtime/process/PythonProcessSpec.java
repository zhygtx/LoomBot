package com.loom.runtime.process;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Python 子进程的启动参数。
 *
 * @param name 用于日志与线程命名，如 {@code adapter-onebot11}
 * @param command 完整命令行，如 {@code ["python", "/path/main.py"]}
 * @param workingDir 工作目录（插件根目录）
 * @param env 追加的环境变量（在继承的父进程环境之上）
 */
public record PythonProcessSpec(
        String name, List<String> command, Path workingDir, Map<String, String> env) {

    public PythonProcessSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        command = List.copyOf(command);
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    public static PythonProcessSpec of(String name, Path workingDir, String... command) {
        return new PythonProcessSpec(name, List.of(command), workingDir, Map.of());
    }
}
