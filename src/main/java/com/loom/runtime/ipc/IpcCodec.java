package com.loom.runtime.ipc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * NDJSON 编解码。
 *
 * <p>刻意用最朴素的 JSON 而不是二进制帧：单人开发时**可调试性 > 性能** —— 出问题可以直接 {@code tail} 管道看懂发生了什么。等压测证明 IPC 是瓶颈再考虑换。
 *
 * <p>二进制数据（WS 二进制帧）由上层用 {@code {"encoding":"base64","content":"..."}} 表达， 本类不管这层语义。
 */
public final class IpcCodec {

    private static final String FIELD_ID = "id";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_PAYLOAD = "payload";

    private final ObjectMapper mapper;

    public IpcCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 解码一行。
     *
     * @throws IpcProtocolException 行不是合法 JSON，或缺少必需字段
     */
    public IpcMessage decode(String line) {
        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (RuntimeException e) {
            throw new IpcProtocolException("不是合法 JSON: " + abbreviate(line), e);
        }
        if (root == null || !root.isObject()) {
            throw new IpcProtocolException("消息必须是 JSON 对象: " + abbreviate(line));
        }
        JsonNode typeNode = root.get(FIELD_TYPE);
        if (typeNode == null || !typeNode.isString() || typeNode.asString().isBlank()) {
            throw new IpcProtocolException("缺少 type 字段: " + abbreviate(line));
        }
        String id = root.hasNonNull(FIELD_ID) ? root.get(FIELD_ID).asString() : null;
        JsonNode payload = root.get(FIELD_PAYLOAD);
        return new IpcMessage(id, typeNode.asString(), payload);
    }

    /** 编码成单行（不含换行符，由调用方补）。 */
    public String encode(IpcMessage message) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        if (message.id() != null) {
            root.put(FIELD_ID, message.id());
        }
        root.put(FIELD_TYPE, message.type());
        if (message.payload() != null) {
            root.set(FIELD_PAYLOAD, message.payload());
        }
        return mapper.writeValueAsString(root);
    }

    /** 构造一个空的 payload 对象，供上层填充。 */
    public ObjectNode newPayload() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static String abbreviate(String line) {
        if (line == null) {
            return "null";
        }
        String trimmed = line.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }
}
