package com.loom.workflow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作流运行时进程配置。
 *
 * @param autoStart Java 是否托管本地工作流运行时进程
 * @param module Python 模块名
 * @param workingDirectory 启动工作目录
 * @param controlToken 内部接口令牌
 * @param testBaseUrl 测试执行内部接口
 * @param startupTimeout 启动超时
 */
@ConfigurationProperties(prefix = "loom.workflow.worker")
public record WorkflowWorkerProperties(
        Boolean autoStart,
        String module,
        String workingDirectory,
        String controlToken,
        String testBaseUrl,
        Duration startupTimeout) {

    public WorkflowWorkerProperties {
        autoStart = autoStart == null || autoStart;
        module = module == null || module.isBlank() ? "system.executor.main" : module.strip();
        workingDirectory =
                workingDirectory == null || workingDirectory.isBlank()
                        ? "python"
                        : workingDirectory.strip();
        controlToken =
                controlToken == null || controlToken.isBlank()
                        ? "loom-dev-workflow-token"
                        : controlToken.strip();
        testBaseUrl =
                testBaseUrl == null || testBaseUrl.isBlank()
                        ? "http://127.0.0.1:9200"
                        : testBaseUrl.strip().replaceAll("/+$", "");
        startupTimeout =
                startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()
                        ? Duration.ofSeconds(20)
                        : startupTimeout;
    }
}
