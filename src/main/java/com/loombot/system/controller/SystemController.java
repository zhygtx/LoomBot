package com.loombot.system.controller;

import com.loombot.common.api.Result;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统信息接口。
 *
 * <p>目前只有连通性探针。它的作用不只是「看看服务活着没」—— 更是 <b>「过滤器 → TraceId → 统一响应」整条链路的端到端验证点</b>：
 *
 * <pre>{@code
 * curl -i http://localhost:8080/api/system/ping
 * # 响应头应有 X-Trace-Id，响应体应为统一 Result 结构
 * }</pre>
 *
 * <p>后续用户 / 角色 / 权限管理接口都放在本模块下。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", "loombot");
        body.put("time", OffsetDateTime.now().toString());
        return Result.success(body);
    }
}
