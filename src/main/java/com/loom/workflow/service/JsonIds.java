package com.loom.workflow.service;

import tools.jackson.databind.JsonNode;

/**
 * 读取工作流定义里的 ID 字段。
 *
 * <p>协议里的 Long 统一按字符串传输，但定义 JSON 由前端直接提交，数字和数字字符串都要能解析。
 */
final class JsonIds {

    private JsonIds() {}

    /** 解析可选的 Long，缺失或空串返回 null，非法值抛 IllegalArgumentException。 */
    static Long parse(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        String text = value.asText("").strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 必须是数字: " + text);
        }
    }
}
