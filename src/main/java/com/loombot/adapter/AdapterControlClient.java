package com.loombot.adapter;

import com.loombot.config.AdapterProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Java 控制面到 Python 监管器的唯一 HTTP 客户端。 */
@Component
public class AdapterControlClient {
    private final AdapterProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public AdapterControlClient(AdapterProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(properties.requestTimeout())
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
    }

    public String publicWsBaseUrl() {
        return properties.publicWsBaseUrl();
    }

    public boolean health() {
        try {
            request("GET", "/internal/health", null);
            return true;
        } catch (AdapterControlException e) {
            return false;
        }
    }

    public AdapterControlResult apply(AdapterConnectionCommand command) {
        return command("/internal/desired/apply", toJson(command));
    }

    public AdapterControlResult remove(long connectionId) {
        JsonNode root = request("DELETE", "/internal/desired/" + connectionId, null);
        return result(root);
    }

    public AdapterControlResult reconcile(List<AdapterConnectionCommand> commands) {
        ObjectNode root = mapper.createObjectNode();
        root.put("snapshotGeneration", System.currentTimeMillis());
        ArrayNode values = root.putArray("connections");
        commands.forEach(command -> values.add(toJson(command)));
        return command("/internal/desired/snapshot", root);
    }

    public Optional<AdapterConnectionStatus> status(long connectionId) {
        return statuses(List.of(connectionId)).values().stream().findFirst();
    }

    public Map<Long, AdapterConnectionStatus> statuses(Collection<Long> connectionIds) {
        JsonNode root = request("GET", "/internal/observations", null);
        JsonNode values = root == null ? null : root.get("observations");
        if (values == null || !values.isArray()) return Map.of();
        Map<Long, AdapterConnectionStatus> result = new LinkedHashMap<>();
        values.forEach(
                node -> {
                    AdapterConnectionStatus status = toStatus(node);
                    if (connectionIds == null || connectionIds.contains(status.connectionId()))
                        result.put(status.connectionId(), status);
                });
        return Map.copyOf(result);
    }

    public JsonNode invoke(long connectionId, String action, JsonNode params) {
        ObjectNode body = mapper.createObjectNode();
        body.put("action", action);
        body.set("params", params == null ? mapper.createObjectNode() : params);
        return request("POST", "/internal/actions/" + connectionId, body);
    }

    /** 推送定时触发快照；适配器层负责 Cron 求值和任务投递。 */
    public JsonNode pushSchedules(List<Map<String, Object>> schedules) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode values = root.putArray("schedules");
        for (Map<String, Object> item : schedules) {
            ObjectNode node = values.addObject();
            item.forEach((key, value) -> node.put(key, value == null ? "" : String.valueOf(value)));
        }
        return request("POST", "/internal/schedules/snapshot", root);
    }

    private AdapterControlResult command(String path, JsonNode body) {
        return result(request("POST", path, body));
    }

    private AdapterControlResult result(JsonNode root) {
        boolean accepted =
                root == null || !root.has("accepted") || root.get("accepted").asBoolean();
        String message =
                root == null ? null : text(root, "errorMessage", text(root, "detail", null));
        List<AdapterConnectionStatus> statuses = new ArrayList<>();
        JsonNode observations = root == null ? null : root.get("observations");
        if (observations != null && observations.isArray())
            observations.forEach(node -> statuses.add(toStatus(node)));
        JsonNode observation = root == null ? null : root.get("observation");
        if (observation != null && observation.isObject()) statuses.add(toStatus(observation));
        return new AdapterControlResult(accepted, message, List.copyOf(statuses));
    }

    private JsonNode request(String method, String path, JsonNode body) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(URI.create(properties.controlBaseUrl() + path))
                        .timeout(properties.requestTimeout())
                        .header("Accept", "application/json");
        if (!properties.controlToken().isBlank())
            builder.header("X-Adapter-Token", properties.controlToken());
        if ("GET".equals(method)) builder.GET();
        else if ("DELETE".equals(method)) builder.DELETE();
        else
            builder.header("Content-Type", "application/json")
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    body == null ? "{}" : body.toString(), StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response =
                    http.send(
                            builder.build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new AdapterControlException(
                        "Adapter 控制 API 返回 " + response.statusCode() + ": " + response.body());
            return response.body().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.body());
        } catch (IOException e) {
            throw new AdapterControlException("Adapter 控制 API 不可达: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdapterControlException("Adapter 控制 API 请求被中断: " + path, e);
        }
    }

    private ObjectNode toJson(AdapterConnectionCommand command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("connectionId", command.connectionId());
        node.put("pluginVersionId", command.pluginVersionId());
        node.put("pluginPath", command.pluginPath());
        node.put("entryPoint", command.entryPoint());
        node.put("pythonPath", command.pythonPath());
        node.put("artifactSha256", command.artifactSha256());
        node.put("revision", command.desiredRevision());
        node.put("enabled", command.enabled());
        node.put("pluginKey", command.pluginKey());
        node.put("pluginVersion", command.pluginVersion());
        node.put("connectionType", command.connectionType());
        node.put("direction", command.direction() == null ? null : command.direction().name());
        node.put("endpointPath", command.endpointPath());
        node.put("configHash", command.configHash());
        node.set("config", command.config());
        ObjectNode adapter = node.putObject("adapter");
        adapter.put("package", command.pluginKey());
        adapter.put("version", command.pluginVersion());
        adapter.put("type", command.connectionType());
        ObjectNode transport = node.putObject("transport");
        transport.put("direction", command.direction() == null ? null : command.direction().name());
        transport.put("endpointId", command.endpointPath());
        return node;
    }

    private AdapterConnectionStatus toStatus(JsonNode node) {
        return new AdapterConnectionStatus(
                longField(node, "connectionId", -1),
                null,
                -1,
                null,
                longField(node, "observedRevision", 0),
                text(node, "state", "PENDING"),
                text(node, "errorMessage", null),
                longField(node, "lastReceivedAt", 0));
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asString();
    }

    private static long longField(JsonNode node, String field, long fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isNumber() ? fallback : value.asLong();
    }
}
