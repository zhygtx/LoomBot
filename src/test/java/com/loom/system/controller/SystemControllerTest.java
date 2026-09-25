package com.loom.system.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.loom.common.api.Result;
import com.loom.common.trace.TraceContext;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("系统信息接口")
class SystemControllerTest {

    private final SystemController controller = new SystemController();

    @AfterEach
    void clear() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("ping 应返回统一结构，且带上应用名与时间")
    void pingShouldReturnUnifiedResult() {
        TraceContext.set("trace-ping01");

        Result<Map<String, Object>> result = controller.ping();

        assertThat(result.ok()).isTrue();
        assertThat(result.traceId()).isEqualTo("trace-ping01");
        assertThat(result.data()).containsEntry("application", "loom");
        assertThat(result.data()).containsKey("time");
    }
}
