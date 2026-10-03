package com.loombot.connection.service;

import com.loombot.common.api.ErrorCode;
import com.loombot.common.exception.BusinessException;
import com.loombot.connection.domain.ConnectionTypeDescriptor;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code config} 的密钥掩码处理与形状校验。
 *
 * <h2>要解决的问题</h2>
 *
 * <p>{@code config} 里存着 bot token 这类凭据。如果查询接口原样返回：
 *
 * <ol>
 *   <li>凭据进入浏览器、进入 HTTP 响应日志、进入前端状态管理 —— 泄漏面被无谓地放大；
 *   <li>更麻烦的是**回写**：前端拿着掩码后的值提交，密钥就被 {@code ********} 覆盖了， 而且不会报错，只在下次重启时表现为「鉴权失败」。
 * </ol>
 *
 * <p>所以读的时候打掩码，写的时候把哨兵还原成原值。前端不需要为此写任何特殊逻辑。
 *
 * <h2>密钥字段从哪来</h2>
 *
 * <p>唯一来源是插件配置 schema 里标了 {@code "x-secret": true} 的字段。传输地址和协议凭据分开表达， 掩码不再依赖握手元数据。
 *
 * <p>刻意**不引入「加密存储」**这一层：字段级加密需要密钥管理，而密钥管理没有 「简单做对」的版本（放配置文件等于没加，放环境变量等于换了个地方放明文）。
 * 这件事留到真正需要时再设计，现在只做「不主动泄漏」。
 *
 * <h2>为什么入参出参都是 {@link JsonNode}</h2>
 *
 * <p>API 两侧形状一致（请求是对象、响应是对象），由 Service 在落库那一刻转成文本。 早期版本这层收发的都是 {@code String}，结果是「前端必须把对象序列化成字符串」——
 * 多余的转义环节，而且 Jackson 会把「对象给到 String 字段」直接判为 400。
 */
public final class ConfigMasking {

    /** 回传哨兵。选 8 个星号：不可能是真实密钥的常见形态，肉眼也好认。 */
    public static final String MASK = "********";

    private static final String X_SECRET = "x-secret";

    private ConfigMasking() {}

    /** 收集该连接类型下所有应打掩码的字段名。 */
    public static Set<String> secretFields(ConnectionTypeDescriptor descriptor) {
        Set<String> fields = new LinkedHashSet<>();
        if (descriptor == null) {
            return fields;
        }
        JsonNode schema = descriptor.configSchema();
        JsonNode properties = schema == null ? null : schema.get("properties");
        if (properties != null && properties.isObject()) {
            // 用 propertyNames() 而不是 fields()/properties()：后两者的名字与返回类型
            // 在 Jackson 2 → 3 之间改过（propertyNames() 在 3 里返回 Collection<String>）
            for (String name : properties.propertyNames()) {
                if (isSecret(properties.get(name))) {
                    fields.add(name);
                }
            }
        }
        return fields;
    }

    /**
     * 校验是合法的 JSON **对象**，并规范化后返回文本（用于落库）。
     *
     * <p>必须是对象：MySQL 的 {@code JSON} 列虽然也接受数组和标量，但适配器拿到的 {@code config}
     * 一定是个字段集合，允许其他形态只会让插件里多出无意义的分支。
     *
     * @throws BusinessException 不是对象，或为 {@code null}
     */
    public static String requireJsonObject(JsonNode config) {
        if (config == null || config.isNull()) {
            throw new BusinessException(ErrorCode.CONNECTION_CONFIG_INVALID, "连接参数不能为空");
        }
        if (!config.isObject()) {
            throw new BusinessException(
                    ErrorCode.CONNECTION_CONFIG_INVALID,
                    "连接参数必须是 JSON 对象，实际是: " + config.getNodeType());
        }
        return config.toString();
    }

    public static String requireValidConfig(JsonNode config, ConnectionTypeDescriptor descriptor) {
        String normalized = requireJsonObject(config);
        JsonNode schema = descriptor == null ? null : descriptor.configSchema();
        if (schema == null || !schema.isObject()) {
            return normalized;
        }
        JsonNode properties = schema.get("properties");
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                String name = field.asString();
                JsonNode value = config.get(name);
                if (value == null
                        || value.isNull()
                        || (value.isString() && value.asString().isBlank())) {
                    throw new BusinessException(
                            ErrorCode.CONNECTION_CONFIG_INVALID, "连接参数缺少必填字段: " + name);
                }
            }
        }
        if (properties != null && properties.isObject()) {
            for (String name : properties.propertyNames()) {
                JsonNode value = config.get(name);
                JsonNode definition = properties.get(name);
                if (value == null || value.isNull() || definition == null) {
                    continue;
                }
                String expected = definition.path("type").asString();
                boolean valid =
                        switch (expected) {
                            case "string" -> value.isString();
                            case "boolean" -> value.isBoolean();
                            case "integer" -> value.isIntegralNumber();
                            case "number" -> value.isNumber();
                            case "object" -> value.isObject();
                            case "array" -> value.isArray();
                            default -> true;
                        };
                if (!valid) {
                    throw new BusinessException(
                            ErrorCode.CONNECTION_CONFIG_INVALID,
                            "连接参数字段 " + name + " 的类型应为 " + expected);
                }
            }
        }
        return normalized;
    }

    /**
     * 把密钥字段替换成哨兵，返回打过掩码的对象。
     *
     * <p>入参是库里读出的**文本**（JDBC 读写 JSON 列都是字符串），出参是对外的对象。
     */
    public static JsonNode mask(
            String storedConfig, Set<String> secretFields, ObjectMapper mapper) {
        ObjectNode node = parseObject(storedConfig, mapper);
        if (node == null) {
            // 库里已有脏数据（手工改过 / 旧版本写坏）时不要把整个查询接口带崩。
            // 返回 null 而不是原样透出，是因为「透出一个不是对象的 config」会让前端
            // 拿到一个它没约定的形状；null 至少是明确的「这条数据有问题」。
            return null;
        }
        if (secretFields.isEmpty()) {
            return node;
        }
        for (String field : secretFields) {
            JsonNode value = node.get(field);
            if (value != null && value.isString() && !value.asString().isEmpty()) {
                node.put(field, MASK);
            }
        }
        return node;
    }

    /**
     * 提交值里的哨兵还原成库里的原值，返回可直接落库的文本。
     *
     * <p>库里本来就没有该字段、却收到哨兵时，**删除该字段**而不是写入哨兵 —— 语义是「用户没改，而它本来也不存在」，写进去一个假的密钥只会在排查时误导人。
     */
    public static String restore(
            JsonNode incoming, String existing, Set<String> secretFields, ObjectMapper mapper) {
        String normalized = requireJsonObject(incoming);
        if (secretFields.isEmpty()) {
            return normalized;
        }
        ObjectNode node = (ObjectNode) incoming.deepCopy();
        ObjectNode previous = parseObject(existing, mapper);
        for (String field : secretFields) {
            JsonNode value = node.get(field);
            if (value == null || !value.isString() || !MASK.equals(value.asString())) {
                continue;
            }
            JsonNode old = previous == null ? null : previous.get(field);
            if (old != null && old.isString() && !old.asString().isEmpty()) {
                node.put(field, old.asString());
            } else {
                node.remove(field);
            }
        }
        return node.toString();
    }

    private static boolean isSecret(JsonNode fieldDefinition) {
        if (fieldDefinition == null) {
            return false;
        }
        JsonNode flag = fieldDefinition.get(X_SECRET);
        return flag != null && flag.isBoolean() && flag.booleanValue();
    }

    /**
     * 宽松解析：任何问题都返回 {@code null}。
     *
     * <p>与写入路径的严格校验分开是有意的。**读路径必须宽松** —— 库里可能已有历史脏数据（手工改过、旧版本写坏过），此时抛错会让整张列表接口挂掉，
     * 用户连「修哪一条」都看不到。**写路径必须严格** —— 让脏数据进库才是真正的问题。
     */
    private static ObjectNode parseObject(String config, ObjectMapper mapper) {
        if (config == null || config.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(config);
            return node.isObject() ? (ObjectNode) node : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
