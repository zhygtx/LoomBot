package com.loom.connection;

import static com.loom.connection.AuthenticationExtension.auth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loom.connection.domain.WsConnection;
import com.loom.connection.manager.ConnectionManager;
import com.loom.connection.mapper.WsConnectionMapper;
import com.loom.connection.service.ConfigMasking;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接 HTTP 接口的集成测试。
 *
 * <p>打 {@code integration} 标签：需要真实 MySQL（Flyway 建表）与 Python 解释器 （启动假适配器）。CI 上默认排除，本地用 {@code mvn
 * verify -Dtest.excluded.groups=} 跑。
 *
 * <p>每个用例用 UUID 生成连接名，测试之间不互相污染，失败留下残渣也不会让下次运行失败。
 *
 * <h2>⚠️ 为什么用 {@code @WithAuthorities} 而不是 {@code @WithMockUser}</h2>
 *
 * <p>{@code @WithMockUser} 依赖 {@code WithSecurityContextTestExecutionListener}， 而那个监听器注册在 {@code
 * spring-security-test} 的 {@code META-INF/spring.factories} —— <b>Spring Boot 4 只扫描 {@code
 * META-INF/spring/*.imports}，不再加载它</b>。
 *
 * <p>实测：手工把监听器挂到 {@code @TestExecutionListeners} 上**也无效**， 13 个用例全部以匿名身份发出、统一收到 401。而且<b>它不报错</b>
 * —— 表现得像「权限配错了」，实际是「注解根本没被处理」。
 *
 * <p>所以改用 {@link WithAuthorities} + {@link AuthenticationExtension}：直接往 {@code
 * SecurityContextHolder} 放认证，不依赖任何自动配置。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ExtendWith(AuthenticationExtension.class)
@DisplayName("连接 HTTP 接口（需真实 MySQL + Python）")
class ConnectionHttpIntegrationTest {

    private static final String TOKEN = "it-token-" + UUID.randomUUID();

    @Autowired private MockMvc mockMvc;

    @Autowired private WsConnectionMapper mapper;

    @Autowired private ConnectionManager manager;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private JdbcTemplate jdbc;

    private String connectionName;
    private Long createdId;

    @BeforeEach
    void awaitAdapter() {
        connectionName = "it-" + UUID.randomUUID();
        awaitFakeAdapter();
    }

    @AfterEach
    void cleanUp() {
        // 物理删除，且按**前缀**而不是精确名：
        //   · 逻辑删除（@TableLogic）下 mapper.deleteById 只置 deleted=1，行还在，
        //     而后续查询又被 deleted=0 过滤掉 —— 结果是每次跑都堆一批永久残留
        //   · 断言失败的用例走不到「记下 id」那一步，只靠内存状态同样会漏
        // 用前缀还顺带清掉历史遗留。详见 TestCleanup 的说明。
        TestCleanup.purge(jdbc, manager, "it-");
        createdId = null;
    }

    private void awaitFakeAdapter() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            if (manager.connectionType("fake").map(t -> t.ready()).orElse(false)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException(
                "假适配器 30 秒内没有就绪。请确认 `python` 在 PATH 上，" + "且 demo/plugins/fake-adapter/main.py 存在");
    }

    // ==================================================================
    // 类型
    // ==================================================================

    @Test
    @WithAuthorities("connection:ws:list")
    @DisplayName("类型接口应返回假适配器声明的类型，含方向、schema 与握手方式")
    void shouldListAdapterDeclaredTypes() throws Exception {
        mockMvc.perform(get("/api/connection/types").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].type").value("fake"))
                .andExpect(jsonPath("$.data[0].direction").value("REVERSE"))
                .andExpect(jsonPath("$.data[0].ready").value(true))
                .andExpect(
                        jsonPath("$['data'][0]['configSchema']['properties']['token']['x-secret']")
                                .value(true))
                .andExpect(jsonPath("$.data[0].handshakeSpec.mode").value("queryParam"));
    }

    @Test
    @DisplayName("未认证时类型接口应返回 401 的统一结构")
    void shouldRejectAnonymous() throws Exception {
        mockMvc.perform(get("/api/connection/types").with(auth()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(200000));
    }

    @Test
    @WithAuthorities("connection:ws:read")
    @DisplayName("权限不足时应返回 403，而不是静默放行")
    void shouldRejectInsufficientAuthority() throws Exception {
        mockMvc.perform(get("/api/connection/types").with(auth()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(200001));
    }

    // ==================================================================
    // 创建
    // ==================================================================

    @Test
    @WithAuthorities("connection:ws:create")
    @DisplayName("创建反向连接应生成接入路径，且响应里的 token 已打掩码")
    void shouldCreateReverseConnectionWithMaskedToken() throws Exception {
        MvcResult result = createConnection();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(TOKEN);
        assertThat(body).contains(ConfigMasking.MASK);

        createdId = readId(result);
        WsConnection stored = mapper.selectById(createdId);
        assertThat(stored.getEndpointPath()).startsWith("/ws/");
        assertThat(stored.getEndpointPath()).hasSize("/ws/".length() + 32);
        // 库里必须还是明文原值 —— 掩码只发生在出站序列化这一层
        assertThat(stored.getConfig()).contains(TOKEN);
    }

    @Test
    @WithAuthorities("connection:ws:create")
    @DisplayName("连接名重复应返回 600001，而不是 500")
    void shouldRejectDuplicateName() throws Exception {
        MvcResult first = createConnection();
        createdId = readId(first);

        mockMvc.perform(
                        post("/api/connection")
                                .with(auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(connectionName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(600001));
    }

    @Test
    @WithAuthorities("connection:ws:create")
    @DisplayName("类型没有适配器声明时应返回 600003")
    void shouldRejectUnknownType() throws Exception {
        mockMvc.perform(
                        post("/api/connection")
                                .with(auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"%s","connectionType":"no-such-type","config":{"a":1}}
                                        """
                                                .formatted(connectionName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(600003));
    }

    @Test
    @WithAuthorities("connection:ws:create")
    @DisplayName("config 不是 JSON 对象时应返回 600004 —— 不能等到写入 MySQL 的 JSON 列才炸")
    void shouldRejectNonObjectConfig() throws Exception {
        mockMvc.perform(
                        post("/api/connection")
                                .with(auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"%s","connectionType":"fake","config":"[1,2]"}
                                        """
                                                .formatted(connectionName)))
                .andExpect(jsonPath("$.code").value(600004));
    }

    @Test
    @WithAuthorities("connection:ws:create")
    @DisplayName("必填项缺失时应返回参数校验错误，且带上字段级提示")
    void shouldRejectBlankName() throws Exception {
        mockMvc.perform(
                        post("/api/connection")
                                .with(auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"","connectionType":"fake","config":{"token":"t"}}
                                        """))
                .andExpect(jsonPath("$.code").value(100000));
    }

    // ==================================================================
    // 查询 / 修改 / 删除
    // ==================================================================

    @Test
    @WithAuthorities({"connection:ws:create", "connection:ws:read", "connection:ws:list"})
    @DisplayName("详情与列表都应能查到刚创建的连接")
    void shouldReadBack() throws Exception {
        createdId = readId(createConnection());

        mockMvc.perform(get("/api/connection/" + createdId).with(auth()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value(connectionName))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.typeReady").value(true));

        mockMvc.perform(get("/api/connection").param("keyword", connectionName).with(auth()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(createdId));
    }

    @Test
    @WithAuthorities({"connection:ws:create", "connection:ws:update"})
    @DisplayName("原样回传掩码时，库里必须保留真实 token —— 这是最容易出错的一条")
    void shouldPreserveSecretWhenMaskEchoedBack() throws Exception {
        createdId = readId(createConnection());

        mockMvc.perform(
                        put("/api/connection/" + createdId)
                                .with(auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"%s","config":{"token":"%s"},"remark":"改了备注"}
                                        """
                                                .formatted(connectionName, ConfigMasking.MASK)))
                .andExpect(jsonPath("$.code").value(0));

        WsConnection stored = mapper.selectById(createdId);
        assertThat(stored.getConfig()).contains(TOKEN);
        assertThat(stored.getConfig()).doesNotContain(ConfigMasking.MASK);
        assertThat(stored.getRemark()).isEqualTo("改了备注");
    }

    @Test
    @WithAuthorities({"connection:ws:create", "connection:ws:update"})
    @DisplayName("填了新 token 时应以新值为准")
    void shouldAcceptNewSecret() throws Exception {
        createdId = readId(createConnection());

        mockMvc.perform(
                        put("/api/connection/" + createdId)
                                .with(auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"%s","config":{"token":"brand-new"}}
                                        """
                                                .formatted(connectionName)))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(mapper.selectById(createdId).getConfig()).contains("brand-new");
    }

    @Test
    @WithAuthorities({"connection:ws:create", "connection:ws:operate"})
    @DisplayName("停用后再启用，运行时状态应跟着走")
    void shouldToggleEnabled() throws Exception {
        createdId = readId(createConnection());

        mockMvc.perform(post("/api/connection/" + createdId + "/disable").with(auth()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(false));
        assertThat(mapper.selectById(createdId).getEnabled()).isZero();

        mockMvc.perform(post("/api/connection/" + createdId + "/enable").with(auth()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true));
        assertThat(mapper.selectById(createdId).getEnabled()).isEqualTo(1);
    }

    @Test
    @WithAuthorities({"connection:ws:create", "connection:ws:delete", "connection:ws:read"})
    @DisplayName("删除后详情应报 600000；重复删除同样报 600000 而不是 500")
    void shouldDeleteThenReportNotFound() throws Exception {
        long id = readId(createConnection());

        mockMvc.perform(delete("/api/connection/" + id).with(auth()))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/connection/" + id).with(auth()))
                .andExpect(jsonPath("$.code").value(600000));
        mockMvc.perform(delete("/api/connection/" + id).with(auth()))
                .andExpect(jsonPath("$.code").value(600000));
    }

    @Test
    @WithAuthorities("connection:ws:list")
    @DisplayName("分页参数越界应被挡下，避免一次捞全表")
    void shouldRejectOversizedPage() throws Exception {
        mockMvc.perform(get("/api/connection").param("pageSize", "100000").with(auth()))
                .andExpect(jsonPath("$.code").value(100000));
        mockMvc.perform(get("/api/connection").param("pageNum", "0").with(auth()))
                .andExpect(jsonPath("$.code").value(100000));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private MvcResult createConnection() throws Exception {
        return mockMvc.perform(
                        post("/api/connection")
                                .with(auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(connectionName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
    }

    private static String createBody(String name) {
        return """
               {"name":"%s","connectionType":"fake","config":{"token":"%s"},"remark":"集成测试"}
               """
                .formatted(name, TOKEN);
    }

    private Long readId(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode id = root.path("data").path("id");
        assertThat(id.isNumber()).as("响应里应包含 data.id: %s", root).isTrue();
        return id.asLong();
    }
}
