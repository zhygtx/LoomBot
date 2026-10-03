package com.loombot.plugin.dto;

/**
 * 新建或更新一个插件库的声明。
 *
 * <p>只覆盖 `repo.json` 里的字段；库的身份（key）在路径上，不在这里，避免两处都能改。
 */
public record PluginRepoRequest(String url, String branch, String scanner) {}
