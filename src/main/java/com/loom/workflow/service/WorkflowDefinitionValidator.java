package com.loom.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loom.connection.domain.WsConnection;
import com.loom.connection.mapper.WsConnectionMapper;
import com.loom.plugin.domain.PluginNode;
import com.loom.plugin.mapper.PluginNodeMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 保存时校验：把「运行时炸」变成「保存时拦住」。
 *
 * <p>校验事件节点恰好一个、无环、节点存在、适配器事件和动作绑定连接、必填参数有来源、 来源节点在可达路径上必然先执行。
 */
@Component
public class WorkflowDefinitionValidator {

    public static final String KIND_ADAPTER = "ADAPTER";
    public static final String KIND_SCHEDULE = "SCHEDULE";

    private static final Set<String> SYSTEM_EVENTS = Set.of("system.schedule");
    private static final String EXPRESSION_ALLOWED = "^[\\s\\w+\\-*/%().,'\"<>=!&|]*$";

    private final PluginNodeMapper nodeMapper;
    private final WsConnectionMapper connectionMapper;
    private final ObjectMapper objectMapper;

    public WorkflowDefinitionValidator(
            PluginNodeMapper nodeMapper,
            WsConnectionMapper connectionMapper,
            ObjectMapper objectMapper) {
        this.nodeMapper = nodeMapper;
        this.connectionMapper = connectionMapper;
        this.objectMapper = objectMapper;
    }

    /** 事件入口信息，用于触发索引和任务投递。 */
    public record EventEntry(
            String nodeId, String kind, Long connectionId, String connectionType, String nodeKey) {}

    public EventEntry validate(JsonNode definition, Long ownerUserId) {
        if (definition == null || !definition.isObject()) {
            throw new IllegalArgumentException("工作流定义必须是 JSON 对象");
        }
        JsonNode nodes = definition.path("nodes");
        if (!nodes.isArray() || nodes.isEmpty()) {
            throw new IllegalArgumentException("工作流定义至少需要一个节点");
        }
        String eventNodeId = definition.path("eventNodeId").asText("").strip();

        Map<String, JsonNode> byId = new LinkedHashMap<>();
        Map<String, PluginNode> catalog = new HashMap<>();
        List<String> eventIds = new ArrayList<>();
        for (JsonNode node : nodes) {
            String id = node.path("id").asText("").strip();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("节点缺少 id");
            }
            if (byId.put(id, node) != null) {
                throw new IllegalArgumentException("节点 id 重复: " + id);
            }
            String nodeKey = node.path("nodeKey").asText("").strip();
            if (nodeKey.isEmpty()) {
                throw new IllegalArgumentException("节点 " + id + " 缺少 nodeKey");
            }
            PluginNode row = catalogFor(node, nodeKey);
            if (row != null) {
                catalog.put(id, row);
            }
            if (isEvent(row, nodeKey)) {
                eventIds.add(id);
            }
            validateBranch(id, node);
        }
        if (eventIds.size() > 1) {
            throw new IllegalArgumentException("工作流最多只能有一个事件节点");
        }
        if (eventIds.size() == 1 && !eventIds.getFirst().equals(eventNodeId)) {
            throw new IllegalArgumentException("eventNodeId 必须指向唯一的事件节点");
        }
        if (eventIds.isEmpty() && !eventNodeId.isEmpty()) {
            throw new IllegalArgumentException("没有事件节点时 eventNodeId 必须为空");
        }

        Map<String, Set<String>> predecessors = buildGraph(byId, definition.path("edges"));
        Map<String, Set<String>> requiredBefore =
                eventNodeId.isEmpty()
                        ? ancestors(predecessors)
                        : dominance(predecessors, eventNodeId);
        validateConnections(byId, catalog, ownerUserId);
        validateInputs(byId, catalog, requiredBefore, eventNodeId);

        if (eventNodeId.isEmpty()) {
            return null;
        }
        JsonNode eventNode = byId.get(eventNodeId);
        String eventKey = eventNode.path("nodeKey").asText("");
        String kind = SYSTEM_EVENTS.contains(eventKey) ? KIND_SCHEDULE : KIND_ADAPTER;
        if (KIND_SCHEDULE.equals(kind)) {
            String cron = eventNode.path("config").path("cron").asText("");
            if (cron.isBlank()) {
                throw new IllegalArgumentException("定时事件必须配置 Cron 表达式");
            }
            CronExpressionSupport.validate(cron);
        }
        Long connectionId = JsonIds.parse(eventNode, "connectionId");
        String connectionType = eventNode.path("connectionType").asText("");
        return new EventEntry(
                eventNodeId,
                kind,
                connectionId,
                connectionType.isEmpty() ? null : connectionType,
                eventKey);
    }

    /**
     * 从定义里取出事件入口，**不跑完整校验**。
     *
     * <p>历史定义可能引用了已经删掉的插件节点，那种定义仍然需要能取出事件入口（比如解绑触发索引）， 所以这条路不能顺手做校验。
     */
    public EventEntry eventEntryOf(JsonNode definition) {
        String eventNodeId = definition.path("eventNodeId").asText("");
        if (eventNodeId.isBlank()) {
            return null;
        }
        for (JsonNode node : definition.path("nodes")) {
            if (!eventNodeId.equals(node.path("id").asText(""))) {
                continue;
            }
            String nodeKey = node.path("nodeKey").asText("");
            String kind = SYSTEM_EVENTS.contains(nodeKey) ? KIND_SCHEDULE : KIND_ADAPTER;
            String connectionType = node.path("connectionType").asText("");
            return new EventEntry(
                    eventNodeId,
                    kind,
                    JsonIds.parse(node, "connectionId"),
                    connectionType.isEmpty() ? null : connectionType,
                    nodeKey);
        }
        return null;
    }

    private PluginNode catalogFor(JsonNode node, String nodeKey) {
        if (SYSTEM_EVENTS.contains(nodeKey)) {
            return null;
        }
        Long pluginVersionId = JsonIds.parse(node, "pluginVersionId");
        if (pluginVersionId == null) {
            throw new IllegalArgumentException("节点 " + nodeKey + " 缺少 pluginVersionId");
        }
        PluginNode row = findNode(pluginVersionId, nodeKey);
        if (row == null) {
            throw new IllegalArgumentException(
                    "节点「"
                            + nodeKey
                            + "」引用的插件已变更或已删除，请重新选择节点后再保存（pluginVersionId="
                            + pluginVersionId
                            + "）");
        }
        return row;
    }

    /** 当前登记的节点签名；节点已经不存在时返回 null。 */
    public String nodeSignatureOf(Long pluginVersionId, String nodeKey) {
        PluginNode row = findNode(pluginVersionId, nodeKey);
        return row == null ? null : row.getSignatureHash();
    }

    private PluginNode findNode(Long pluginVersionId, String nodeKey) {
        return nodeMapper.selectOne(
                new LambdaQueryWrapper<PluginNode>()
                        .eq(PluginNode::getPluginVersionId, pluginVersionId)
                        .eq(PluginNode::getNodeKey, nodeKey));
    }

    private boolean isEvent(PluginNode row, String nodeKey) {
        if (SYSTEM_EVENTS.contains(nodeKey)) {
            return true;
        }
        return row != null && "EVENT".equalsIgnoreCase(row.getNodeType());
    }

    private void validateBranch(String id, JsonNode node) {
        JsonNode branch = node.path("branch");
        if (branch.isMissingNode() || branch.isNull()) {
            return;
        }
        String mode = branch.path("mode").asText("truthy");
        if (!mode.equals("truthy") && !mode.equals("expression")) {
            throw new IllegalArgumentException("节点 " + id + " 的分支模式必须是 truthy 或 expression");
        }
        if (mode.equals("expression")) {
            String expression = branch.path("expression").asText("").strip();
            if (expression.isEmpty()) {
                throw new IllegalArgumentException("节点 " + id + " 使用表达式分支但没有表达式");
            }
            if (expression.length() > 200 || !expression.matches(EXPRESSION_ALLOWED)) {
                throw new IllegalArgumentException("节点 " + id + " 的分支表达式包含不允许的字符");
            }
            if (expression.contains("__")) {
                throw new IllegalArgumentException("节点 " + id + " 的分支表达式包含不允许的名字");
            }
        }
    }

    private Map<String, Set<String>> buildGraph(Map<String, JsonNode> byId, JsonNode edges) {
        Map<String, Set<String>> predecessors = new HashMap<>();
        Map<String, Set<String>> successors = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String id : byId.keySet()) {
            predecessors.put(id, new LinkedHashSet<>());
            successors.put(id, new LinkedHashSet<>());
            indegree.put(id, 0);
        }
        for (JsonNode edge : edges) {
            String from = edge.path("from").asText("");
            String to = edge.path("to").asText("");
            if (!byId.containsKey(from) || !byId.containsKey(to)) {
                throw new IllegalArgumentException("连线引用了不存在的节点：" + from + " -> " + to);
            }
            // 同一对节点之间允许并行连线（分支合流），依赖关系只算一条
            if (!predecessors.get(to).add(from)) {
                continue;
            }
            successors.get(from).add(to);
            indegree.merge(to, 1, Integer::sum);
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach(
                (id, degree) -> {
                    if (degree == 0) {
                        queue.add(id);
                    }
                });
        int visited = 0;
        Map<String, Integer> working = new HashMap<>(indegree);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            visited++;
            for (String target : successors.getOrDefault(id, Set.of())) {
                int remain = working.merge(target, -1, Integer::sum);
                if (remain == 0) {
                    queue.add(target);
                }
            }
        }
        if (visited != byId.size()) {
            throw new IllegalArgumentException("工作流定义存在环，DAG 不允许循环");
        }
        return predecessors;
    }

    private Map<String, Set<String>> ancestors(Map<String, Set<String>> predecessors) {
        Map<String, Set<String>> ancestors = new HashMap<>();
        for (String id : predecessors.keySet()) {
            Set<String> values = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>(predecessors.getOrDefault(id, Set.of()));
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!values.add(current)) {
                    continue;
                }
                queue.addAll(predecessors.getOrDefault(current, Set.of()));
            }
            ancestors.put(id, values);
        }
        return ancestors;
    }

    private Map<String, Set<String>> dominance(
            Map<String, Set<String>> predecessors, String eventNodeId) {
        Map<String, Set<String>> dominance = new HashMap<>();
        Set<String> all = new HashSet<>(predecessors.keySet());
        for (String id : predecessors.keySet()) {
            dominance.put(
                    id, id.equals(eventNodeId) ? new HashSet<>(Set.of(id)) : new HashSet<>(all));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String id : predecessors.keySet()) {
                if (id.equals(eventNodeId)) {
                    continue;
                }
                Set<String> preds = predecessors.get(id);
                if (preds.isEmpty()) {
                    continue;
                }
                Set<String> intersection = null;
                for (String pred : preds) {
                    Set<String> values = dominance.get(pred);
                    if (intersection == null) {
                        intersection = new HashSet<>(values);
                    } else {
                        intersection.retainAll(values);
                    }
                }
                Set<String> next = intersection == null ? new HashSet<>() : intersection;
                next.add(id);
                if (!next.equals(dominance.get(id))) {
                    dominance.put(id, next);
                    changed = true;
                }
            }
        }
        return dominance;
    }

    private void validateConnections(
            Map<String, JsonNode> byId, Map<String, PluginNode> catalog, Long ownerUserId) {
        for (Map.Entry<String, JsonNode> entry : byId.entrySet()) {
            JsonNode node = entry.getValue();
            PluginNode row = catalog.get(entry.getKey());
            boolean needsConnection = row != null && row.getConnectionType() != null;
            if (!needsConnection) {
                continue;
            }
            Long connectionId = JsonIds.parse(node, "connectionId");
            if (connectionId == null) {
                throw new IllegalArgumentException(
                        "节点 " + node.path("nodeKey").asText("") + " 必须绑定连接");
            }
            WsConnection connection = connectionMapper.selectById(connectionId);
            if (connection == null) {
                throw new IllegalArgumentException("连接不存在: " + connectionId);
            }
            if (ownerUserId != null && !ownerUserId.equals(connection.getOwnerUserId())) {
                throw new IllegalArgumentException("连接不属于当前用户: " + connectionId);
            }
            if (!Integer.valueOf(1).equals(connection.getEnabled())) {
                throw new IllegalArgumentException("连接未启用: " + connectionId);
            }
            if (row.getConnectionType() != null
                    && !row.getConnectionType().equals(connection.getConnectionType())) {
                throw new IllegalArgumentException(
                        "节点 " + node.path("nodeKey").asText("") + " 的连接类型与所选连接不一致");
            }
        }
    }

    private void validateInputs(
            Map<String, JsonNode> byId,
            Map<String, PluginNode> catalog,
            Map<String, Set<String>> dominance,
            String eventNodeId) {
        for (Map.Entry<String, JsonNode> entry : byId.entrySet()) {
            String id = entry.getKey();
            if (id.equals(eventNodeId)) {
                continue;
            }
            JsonNode node = entry.getValue();
            Set<String> provided = new HashSet<>();
            for (JsonNode input : node.path("inputs")) {
                String name = input.path("paramName").asText("").strip();
                if (name.isEmpty()) {
                    continue;
                }
                String source = input.path("source").asText("").strip();
                boolean hasDefault =
                        input.has("defaultValue") && !input.path("defaultValue").isNull();
                // 只有真正配了引用或默认值才算「已提供」；两者都没有的参数即便列在 inputs 里也不算数。
                if (source.isEmpty() && !hasDefault) {
                    continue;
                }
                provided.add(name);
                if (source.isEmpty()) {
                    continue;
                }
                String sourceNode = source.split("\\.")[0];
                if (!sourceNode.equals("input") && !byId.containsKey(sourceNode)) {
                    throw new IllegalArgumentException("节点 " + id + " 的来源引用了不存在的节点：" + source);
                }
                if (!sourceNode.equals("input")
                        && !dominance.getOrDefault(id, Set.of()).contains(sourceNode)) {
                    throw new IllegalArgumentException(
                            "节点 " + id + " 的来源 " + source + " 在部分路径上可能尚未执行");
                }
            }
            for (String required : requiredParameters(catalog.get(id))) {
                if (!provided.contains(required)) {
                    throw new IllegalArgumentException(
                            "节点 "
                                    + node.path("nodeKey").asText("")
                                    + " 的必填参数 "
                                    + required
                                    + " 没有配置来源或默认值");
                }
            }
        }
    }

    private List<String> requiredParameters(PluginNode row) {
        List<String> required = new ArrayList<>();
        if (row == null || row.getInputSchema() == null || row.getInputSchema().isBlank()) {
            return required;
        }
        try {
            JsonNode schema = objectMapper.readTree(row.getInputSchema());
            if (schema.isArray()) {
                for (JsonNode parameter : schema) {
                    if (parameter.path("required").asBoolean(false)) {
                        required.add(parameter.path("name").asText(""));
                    }
                }
            } else if (schema.path("required").isArray()) {
                for (JsonNode name : schema.path("required")) {
                    required.add(name.asText(""));
                }
            }
        } catch (RuntimeException e) {
            // 模式不合法时不在保存阶段拦截，交给运行时按参数规则处理
            return required;
        }
        required.removeIf(String::isBlank);
        return required;
    }
}
