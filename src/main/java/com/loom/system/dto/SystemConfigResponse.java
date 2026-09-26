package com.loom.system.dto;

/**
 * 系统配置的对外结构。
 *
 * <p>一条配置就是一个键 + 一个 JSON 值。所以这里**没有 {@code enabled} 字段** —— 开关是值里的 一个键（约定叫 {@code
 * enabled}），不是独立于值之外的状态。表里也没有 status 列和 value_type 列。
 *
 * <p>{@code valueKind} 是**从值本身算出来的**形状（{@code OBJECT} / {@code ARRAY} / {@code BOOLEAN} / {@code
 * NUMBER} / {@code STRING} / {@code NULL}），前端据此挑编辑器；解析不出来时是 {@code
 * INVALID}，配置页把它显示成一条待修复的脏数据，而不是让页面 500。它不是数据库里的列 —— 再存一列类型等于给同一件事留两个可以互相矛盾的真相来源。
 *
 * <p>{@code switchKey} 是值里那个「开关」的键名，没有开关（纯参数配置）时为 null。后端只做一件事： 值是个 JSON 对象且**含 {@code enabled}
 * 键**时把它认出来。至于这个键代表什么、能不能关， 取决于这条配置自己的意思，由使用方解释 —— 后端不替它决定。
 *
 * <p>{@code locked} / {@code lockedReason} 是「这条配置不允许被关掉」的规则，由 {@code SystemConfigService}
 * 里的注册表给出（规则放代码里，不在表上加标志列）。理由原样回给前端，让配置页 **提前**把开关置灰并显示原因，而不是等用户点了保存才收到报错。{@code lockedReason} 在
 * {@code locked} 为 false 时是 null。
 *
 * <p>为什么不直接把 {@code SystemConfig} 实体当响应体返回：实体里的 {@code builtin} 是 tinyint，Jackson 会原样序列化成 {@code 1
 * / 0}。这里统一转成 boolean，和 {@code MenuResponse}、 {@code BackendPermissionResponse} 的做法一致 ——
 * 数据库的存储细节不该漏到接口契约里。
 *
 * @param switchKey 值里「开关」的键名；纯参数配置为 null
 */
public record SystemConfigResponse(
        Long id,
        String configKey,
        String configValue,
        String valueKind,
        String configGroup,
        String name,
        String description,
        boolean builtin,
        String switchKey,
        boolean locked,
        String lockedReason) {}
