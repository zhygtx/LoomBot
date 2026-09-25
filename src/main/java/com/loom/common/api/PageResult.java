package com.loom.common.api;

import java.util.List;

/**
 * 分页响应体，作为 {@link Result} 的 {@code data} 使用。
 *
 * <p><b>为什么不用 MyBatis-Plus 的 {@code IPage}？</b>实测 {@code Page} 被 Jackson 序列化后只输出 {@code current /
 * pages / records / size / total} 五个字段，输出本身是干净的 —— {@code optimizeCountSql()}、{@code
 * searchCount()}、{@code maxLimit()}、{@code countId()}、{@code hasPrevious()} 都不符合 Jackson 的 getter
 * 命名约定，不会被序列化。<b>所以不存在「字段污染」问题。</b>
 *
 * <p>真正的问题是<b>类型耦合</b>：一旦 Controller 返回 {@code IPage}，API 契约就由 ORM 的类型定义了。换 ORM（MyBatis-Plus → JPA
 * / jOOQ），或拆分微服务时把 API DTO 抽到共享包，都会直接破坏接口。
 *
 * <p>这条边界由 {@code ArchitectureTest} 强制：Controller 不允许依赖 {@code com.baomidou.mybatisplus..}，返回
 * {@code IPage} 会直接构建失败。换言之，这不是「多写一个类」的偏好，而是一条可机械校验的架构约束。
 *
 * @param records 当前页数据
 * @param total 总记录数
 * @param pageNum 当前页码（从 1 开始）
 * @param pageSize 每页条数
 * @param pages 总页数
 */
public record PageResult<T>(List<T> records, long total, long pageNum, long pageSize, long pages) {

    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        long pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(records, total, pageNum, pageSize, pages);
    }

    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(List.of(), 0L, pageNum, pageSize, 0L);
    }
}
