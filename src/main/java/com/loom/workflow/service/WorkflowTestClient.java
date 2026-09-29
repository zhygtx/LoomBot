package com.loom.workflow.service;

import com.loom.workflow.WorkflowWorkerProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Java 控制面调用工作流运行时同步测试接口。 */
@Component
public class WorkflowTestClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(75);

    private final WorkflowWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public WorkflowTestClient(WorkflowWorkerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
    }

    public JsonNode test(long versionId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("workflowVersionId", versionId);
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(properties.testBaseUrl() + "/internal/workflow/test"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("X-Workflow-Token", properties.controlToken())
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        body.toString(), StandardCharsets.UTF_8))
                        .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "工作流测试接口返回 " + response.statusCode() + ": " + response.body().strip());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("工作流测试接口不可达", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工作流测试请求被中断", e);
        }
    }
}
