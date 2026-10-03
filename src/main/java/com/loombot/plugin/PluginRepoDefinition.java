package com.loombot.plugin;

import java.nio.file.Path;

/**
 * 一个插件库的声明，来自库文件夹里的 `repo.json`。
 *
 * <p>身份是 {@code key}，不是文件夹名：文件夹可以改名、可以搬家，只要 `repo.json` 里的 key 不变， 库的同步状态和它注册出来的插件就还是同一个。
 *
 * @param key 稳定标识，插件库里注册出来的行按它关联
 * @param url Git 地址；为空表示纯本地库，不做 clone/pull
 * @param branch 分支
 * @param scanner 子扫描器：内置的 loombot，或 local（用库文件夹里的 scanner.py）
 * @param folder 插件库文件夹（含 repo.json、repo/、可选的 scanner.py）
 * @param workingCopy 工作副本目录（folder/repo），插件源在这里
 */
public record PluginRepoDefinition(
        String key, String url, String branch, String scanner, Path folder, Path workingCopy) {}
