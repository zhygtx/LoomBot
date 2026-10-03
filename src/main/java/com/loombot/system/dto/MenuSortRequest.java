package com.loombot.system.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 菜单拖拽排序请求。
 *
 * <p>只提交**受影响的层级**，不提交整棵树：菜单树的绝大部分层级没动，全量提交既浪费也会让 「谁改了哪一层」在日志里看不出来。
 */
public record MenuSortRequest(@NotNull(message = "排序分组不能为空") List<@Valid MenuSortGroup> groups) {}
