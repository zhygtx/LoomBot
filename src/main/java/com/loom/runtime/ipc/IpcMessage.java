package com.loom.runtime.ipc;

import tools.jackson.databind.JsonNode;

/**
 * Java ↔ Python 的消息信封。
 *
 * <p>线格式：{@code {"id":"...","type":"...","payload":{...}}}，一行一条（NDJSON）。
 *
 * @param id 可选。**仅用于请求-响应配对** —— Java 发出的请求带 id，对方回 {@code reply} 时原样带回。 Python 主动发起的消息（如 {@code
 *     event.matched}）不带 id。
 * @param type 消息类型，如 {@code hello} / {@code ws.frame} / {@code event.matched}
 * @param payload 消息体。可能为 {@code null}（如 {@code shutdown}）
 */
public record IpcMessage(String id, String type, JsonNode payload) {

    public static final String TYPE_REPLY = "reply";

    /** 无 id 的消息（Python 主动发起）。 */
    public static IpcMessage of(String type, JsonNode payload) {
        return new IpcMessage(null, type, payload);
    }

    /** 带 id 的消息（需要对方回复）。 */
    public static IpcMessage request(String id, String type, JsonNode payload) {
        return new IpcMessage(id, type, payload);
    }

    public boolean isReply() {
        return TYPE_REPLY.equals(type);
    }

    /** payload 中取字符串字段，缺失时返回 {@code null}。 */
    public String stringField(String name) {
        JsonNode node = field(name);
        return node == null || node.isNull() ? null : node.asString();
    }

    /** payload 中取字段，缺失时返回 {@code null}。 */
    public JsonNode field(String name) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get(name);
        return node == null || node.isNull() ? null : node;
    }
}
