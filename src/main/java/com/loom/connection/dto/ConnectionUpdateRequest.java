package com.loom.connection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * 修改连接的请求。
 *
 * <h2>为什么没有 {@code connectionType}</h2>
 *
 * <p>连接类型决定 {@code config} 的字段构成、方向、握手方式。允许改类型等于 「用 A 插件的参数去配 B 插件」，几乎必然产生一条语义错误但语法合法的脏数据。
 * 要换类型就删掉重建 —— 代价只是一次复制粘贴，换来的是一条不可能出现的错误状态。
 *
 * <h2>{@code config} 是 JSON 对象；密钥字段用掩码哨兵回传</h2>
 *
 * <p>与 {@link ConnectionCreateRequest} 一样用 {@link JsonNode}：请求形状与响应形状一致。
 *
 * <p>查询接口返回的 {@code config} 会把密钥字段替换成 {@code ********}。 前端原样回传时 Service
 * 会认出哨兵并**保留数据库里的原值**，而不是真的把密钥写成星号。 所以前端不需要为这个字段做任何特殊处理。
 */
public record ConnectionUpdateRequest(
        @NotBlank(message = "连接名不能为空") @Size(max = 64, message = "连接名最长 64 字符") String name,
        @NotNull(message = "连接参数不能为空") JsonNode config,
        @Size(max = 255, message = "备注最长 255 字符") String remark) {}
