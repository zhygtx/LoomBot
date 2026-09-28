package com.loom.connection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * 创建连接的请求。
 *
 * <h2>⚠️ {@code config} 是 JSON 对象，不是字符串</h2>
 *
 * <p>早期版本把它声明成 {@code String}，理由是「数据库列是 JSON、JDBC 读写是字符串」。 <b>那个理由只对持久化成立，对 API 不成立</b>：前端拿到的
 * {@code /types} 里 {@code configSchema} 是一个 JSON Schema 对象，按它渲染出来的表单值自然也是个对象。
 * 要求客户端把对象再序列化成字符串塞进来，等于凭空造一道转义环节 —— 而 Jackson 会把「对象给到 String 字段」判为不可读，直接 400。
 *
 * <p>用 {@link JsonNode} 的好处是**两端形状一致**：请求是对象、响应也是对象， 只有落库那一刻才由 Service 转成文本。
 *
 * <p><b>为什么必须是对象而不是任意 JSON</b>：适配器拿到的 {@code config} 一定是个字段集合， 允许数组或标量只会让插件里多出无意义的分支。校验在 Service
 * 里做（见 {@code ConfigMasking.requireJsonObject}）。
 *
 * @param name 连接名，全局唯一
 * @param pluginVersionId 所选适配器插件版本
 * @param connectionType 连接类型，必须能被当前在线的适配器声明
 * @param config 协议特有参数
 * @param remark 备注
 */
public record ConnectionCreateRequest(
        @NotBlank(message = "连接名不能为空") @Size(max = 64, message = "连接名最长 64 字符") String name,
        @NotNull(message = "适配器插件版本不能为空") Long pluginVersionId,
        @NotBlank(message = "连接类型不能为空") @Size(max = 64, message = "连接类型最长 64 字符")
                String connectionType,
        @NotNull(message = "连接参数不能为空") JsonNode config,
        @Size(max = 255, message = "备注最长 255 字符") String remark) {}
