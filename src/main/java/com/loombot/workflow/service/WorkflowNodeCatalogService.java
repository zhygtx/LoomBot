package com.loombot.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loombot.plugin.domain.Plugin;
import com.loombot.plugin.domain.PluginNode;
import com.loombot.plugin.domain.PluginVersion;
import com.loombot.plugin.mapper.PluginMapper;
import com.loombot.plugin.mapper.PluginNodeMapper;
import com.loombot.plugin.mapper.PluginVersionMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 画布用的节点目录：合并插件节点与系统节点。
 *
 * <p>节点目录来自插件扫描（plugin_node），画布不解析插件文件。系统节点由运行时内置， 目前只有定时事件；子工作流节点留到后续实现。
 */
@Service
public class WorkflowNodeCatalogService {

    private final PluginNodeMapper nodeMapper;
    private final PluginVersionMapper versionMapper;
    private final PluginMapper pluginMapper;
    private final ObjectMapper objectMapper;

    public WorkflowNodeCatalogService(
            PluginNodeMapper nodeMapper,
            PluginVersionMapper versionMapper,
            PluginMapper pluginMapper,
            ObjectMapper objectMapper) {
        this.nodeMapper = nodeMapper;
        this.versionMapper = versionMapper;
        this.pluginMapper = pluginMapper;
        this.objectMapper = objectMapper;
    }

    /** 全部可用节点：插件节点 + 系统节点。 */
    public Map<String, Object> catalog() {
        List<PluginNode> rows =
                nodeMapper.selectList(
                        new LambdaQueryWrapper<PluginNode>()
                                .orderByAsc(PluginNode::getNodeType)
                                .orderByAsc(PluginNode::getSort)
                                .orderByAsc(PluginNode::getNodeKey));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (PluginNode row : rows) {
            nodes.add(toItem(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodes", nodes);
        body.put("systemNodes", systemNodes());
        return body;
    }

    private Map<String, Object> toItem(PluginNode row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("pluginVersionId", row.getPluginVersionId());
        item.put("pluginKey", pluginKeyOf(row.getPluginVersionId()));
        item.put("pluginVersion", pluginVersionOf(row.getPluginVersionId()));
        item.put("nodeKey", row.getNodeKey());
        item.put("nodeType", row.getNodeType());
        item.put("connectionType", row.getConnectionType());
        item.put("name", row.getName());
        item.put("description", row.getDescription());
        item.put("sourceRef", row.getSourceRef());
        item.put("signatureHash", row.getSignatureHash());
        item.put("parameters", parametersOf(row.getInputSchema()));
        List<Object> returnFields = returnFieldsOf(row.getOutputSchema());
        item.put("returnFields", returnFields);
        item.put("returnType", returnTypeOf(returnFields));
        return item;
    }

    /** 返回字段里 path 为空的那一条就是「整个返回值」，它的类型即节点返回类型。 */
    private String returnTypeOf(List<Object> returnFields) {
        for (Object field : returnFields) {
            if (field instanceof Map<?, ?> item) {
                String path = item.get("path") == null ? "" : String.valueOf(item.get("path"));
                Object type = item.get("type");
                if (path.isBlank() && type != null && !String.valueOf(type).isBlank()) {
                    return String.valueOf(type);
                }
            }
        }
        return "void";
    }

    private List<Map<String, Object>> parametersOf(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return List.of();
        }
        JsonNode schema;
        try {
            schema = objectMapper.readTree(inputSchema);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<Map<String, Object>> parameters = new ArrayList<>();
        if (schema.isArray()) {
            // 工作流节点：扫描时写入的参数数组
            for (JsonNode parameter : schema) {
                parameters.add(toParameter(parameter, parameter.path("required").asBoolean(false)));
            }
            return parameters;
        }
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return List.of();
        }
        int order = 0;
        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            JsonNode definition = entry.getValue();
            boolean required = false;
            for (JsonNode name : schema.path("required")) {
                if (entry.getKey().equals(name.asText(""))) {
                    required = true;
                    break;
                }
            }
            Map<String, Object> parameter = new LinkedHashMap<>();
            parameter.put("name", entry.getKey());
            parameter.put("displayName", definition.path("title").asText(entry.getKey()));
            parameter.put("type", definition.path("type").asText("object"));
            parameter.put("required", required);
            parameter.put("nullable", false);
            parameter.put("kind", "POSITIONAL");
            parameter.put("hasDefault", definition.has("default"));
            parameter.put(
                    "defaultValue",
                    definition.has("default") ? definition.path("default").toString() : null);
            parameter.put("description", definition.path("description").asText(""));
            parameter.put("order", order++);
            parameters.add(parameter);
        }
        return parameters;
    }

    private Map<String, Object> toParameter(JsonNode parameter, boolean required) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", parameter.path("name").asText(""));
        item.put(
                "displayName",
                parameter.path("displayName").asText(parameter.path("name").asText("")));
        item.put("type", parameter.path("type").asText("object"));
        item.put("required", required);
        item.put("nullable", parameter.path("nullable").asBoolean(false));
        item.put("multiType", parameter.path("multiType").asBoolean(false));
        item.put(
                "literals",
                parameter.path("literals").isArray() ? parameter.path("literals") : List.of());
        item.put(
                "enumValues",
                parameter.path("enumValues").isArray() ? parameter.path("enumValues") : List.of());
        item.put("kind", parameter.path("kind").asText("POSITIONAL"));
        item.put("hasDefault", parameter.path("hasDefault").asBoolean(false));
        item.put(
                "defaultValue",
                parameter.has("defaultValue") ? parameter.path("defaultValue").toString() : null);
        item.put("description", parameter.path("description").asText(""));
        item.put("order", parameter.path("order").asInt(0));
        return item;
    }

    private List<Object> returnFieldsOf(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node.isArray()) {
                List<Object> items = new ArrayList<>();
                node.forEach(
                        element -> {
                            Map<String, Object> item =
                                    objectMapper.convertValue(element, Map.class);
                            String path =
                                    item.get("path") == null
                                            ? ""
                                            : String.valueOf(item.get("path"));
                            item.putIfAbsent("displayName", item.get("name"));
                            item.putIfAbsent("depth", pathDepth(path));
                            if (path != null && !path.isBlank()) {
                                item.putIfAbsent("key", path.substring(path.lastIndexOf('.') + 1));
                            }
                            items.add(item);
                        });
                return items;
            }
            if (node.isObject()) {
                return jsonSchemaReturnFields(node);
            }
            return List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<Object> jsonSchemaReturnFields(JsonNode schema) {
        List<Object> fields = new ArrayList<>();
        Map<String, Object> whole = new LinkedHashMap<>();
        whole.put("name", "整个返回值");
        whole.put("displayName", "整个返回值");
        whole.put("type", schema.path("type").asText("object"));
        whole.put("path", "");
        whole.put("description", schema.path("description").asText(""));
        whole.put("depth", 0);
        fields.add(whole);
        appendSchemaFields(schema, "", fields);
        return fields;
    }

    private void appendSchemaFields(JsonNode schema, String prefix, List<Object> fields) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            String name = entry.getKey();
            String path = prefix.isBlank() ? name : prefix + "." + name;
            JsonNode definition = entry.getValue();
            String type = definition.path("type").asText("object");
            if ("array".equals(type)) {
                String itemType = definition.path("items").path("type").asText("object");
                type = "list[" + itemType + "]";
            }
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name);
            field.put("displayName", definition.path("title").asText(name));
            field.put("key", name);
            field.put("type", type);
            field.put("path", path);
            field.put("description", definition.path("description").asText(""));
            field.put("depth", pathDepth(path));
            fields.add(field);
            if ("object".equals(definition.path("type").asText())) {
                appendSchemaFields(definition, path, fields);
            }
        }
    }

    private int pathDepth(String path) {
        if (path == null || path.isBlank()) {
            return 0;
        }
        return path.split("\\.").length - 1;
    }

    private List<Map<String, Object>> systemNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("nodeKey", "system.schedule");
        schedule.put("nodeType", "EVENT");
        schedule.put("name", "定时任务");
        schedule.put("description", "按 Cron 表达式定时触发，最短间隔 1 秒");
        Map<String, Object> cron = new LinkedHashMap<>();
        cron.put("name", "cron");
        cron.put("displayName", "Cron 表达式");
        cron.put("type", "str");
        cron.put("required", true);
        cron.put("description", "5 或 6 字段，例如 0 0 8 * * ?");
        cron.put("order", 0);
        schedule.put("parameters", List.of(cron));
        nodes.add(schedule);
        return nodes;
    }

    private String pluginKeyOf(Long pluginVersionId) {
        PluginVersion version = versionMapper.selectById(pluginVersionId);
        if (version == null) {
            return "";
        }
        Plugin plugin = pluginMapper.selectById(version.getPluginId());
        return plugin == null ? "" : plugin.getPluginKey();
    }

    private String pluginVersionOf(Long pluginVersionId) {
        PluginVersion version = versionMapper.selectById(pluginVersionId);
        return version == null ? "" : version.getVersion();
    }
}
