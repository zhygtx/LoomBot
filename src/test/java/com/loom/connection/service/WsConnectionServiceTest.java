package com.loom.connection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loom.common.api.ErrorCode;
import com.loom.common.exception.BusinessException;
import com.loom.connection.domain.ConnectionState;
import com.loom.connection.domain.ConnectionStatus;
import com.loom.connection.domain.ConnectionTypeDescriptor;
import com.loom.connection.domain.Direction;
import com.loom.connection.domain.WsConnection;
import com.loom.connection.dto.ConnectionCreateRequest;
import com.loom.connection.dto.ConnectionResponse;
import com.loom.connection.dto.ConnectionUpdateRequest;
import com.loom.connection.handshake.HandshakeSpec;
import com.loom.connection.manager.ConnectionManager;
import com.loom.connection.mapper.WsConnectionMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("连接服务")
class WsConnectionServiceTest {

    private static final String SCHEMA =
            """
            {"type":"object","properties":{"token":{"type":"string","x-secret":true}}}
            """;

    @Mock private WsConnectionMapper mapper;

    @Mock private ConnectionManager manager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WsConnectionService service;

    @BeforeEach
    void setUp() {
        service = new WsConnectionService(mapper, manager, objectMapper);
        lenient()
                .when(manager.status(anyLong()))
                .thenReturn(new ConnectionStatus(1L, true, ConnectionState.ONLINE, null, 0));
    }

    private JsonNode json(String text) {
        return objectMapper.readTree(text);
    }

    private ConnectionTypeDescriptor descriptor(Direction direction) {
        return new ConnectionTypeDescriptor(
                "fake",
                "假适配器",
                direction,
                objectMapper.readTree(SCHEMA),
                new HandshakeSpec("queryParam", "token", "access_token", null, null),
                List.of("ws_parser:fake"),
                "fake-adapter",
                true);
    }

    /** 让 insert 表现得像真数据库：回填自增 ID，并让随后的 selectById 能查到。 */
    private void stubInsertAndReload(WsConnection... stored) {
        when(mapper.insert(any(WsConnection.class)))
                .thenAnswer(
                        invocation -> {
                            WsConnection entity = invocation.getArgument(0);
                            entity.setId(7L);
                            stored[0] = entity;
                            return 1;
                        });
        when(mapper.selectById(7L)).thenAnswer(invocation -> stored[0]);
    }

    @Nested
    @DisplayName("创建")
    class 创建 {

        @Test
        @DisplayName("反向连接应生成接入路径，且路径不可预测")
        void shouldGenerateEndpointPathForReverse() {
            WsConnection[] holder = new WsConnection[1];
            stubInsertAndReload(holder);
            when(manager.connectionType("fake"))
                    .thenReturn(Optional.of(descriptor(Direction.REVERSE)));
            when(mapper.exists(any())).thenReturn(false);

            ConnectionResponse response =
                    service.create(
                            new ConnectionCreateRequest(
                                    "c1", "fake", json("{\"token\":\"t\"}"), null),
                            1L);

            assertThat(response.endpointPath()).startsWith("/ws/");
            assertThat(response.endpointPath()).hasSize("/ws/".length() + 32);
            // 路径必须已经落库，否则重启后端点就丢了
            assertThat(holder[0].getEndpointPath()).isEqualTo(response.endpointPath());
            verify(manager).start(7L);
        }

        @Test
        @DisplayName("正向连接不应生成接入路径 —— 它是客户端，没有接入这回事")
        void shouldNotGenerateEndpointPathForForward() {
            WsConnection[] holder = new WsConnection[1];
            stubInsertAndReload(holder);
            when(manager.connectionType("fake"))
                    .thenReturn(Optional.of(descriptor(Direction.FORWARD)));

            ConnectionResponse response =
                    service.create(
                            new ConnectionCreateRequest(
                                    "c1", "fake", json("{\"token\":\"t\"}"), null),
                            1L);

            assertThat(response.endpointPath()).isNull();
            verify(mapper, never()).exists(any());
        }

        @Test
        @DisplayName("生成路径连续冲突时应重试，而不是直接失败")
        void shouldRetryOnPathCollision() {
            WsConnection[] holder = new WsConnection[1];
            stubInsertAndReload(holder);
            when(manager.connectionType("fake"))
                    .thenReturn(Optional.of(descriptor(Direction.REVERSE)));
            // 前两次说「被占了」，第三次可用了
            when(mapper.exists(any())).thenReturn(true, true, false);

            ConnectionResponse response =
                    service.create(
                            new ConnectionCreateRequest(
                                    "c1", "fake", json("{\"token\":\"t\"}"), null),
                            1L);

            assertThat(response.endpointPath()).isNotNull();
            verify(mapper, times(3)).exists(any());
        }

        @Test
        @DisplayName("适配器未就绪时应拒绝创建，并明确告知原因")
        void shouldRejectWhenAdapterNotReady() {
            when(manager.connectionType("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(
                            () ->
                                    service.create(
                                            new ConnectionCreateRequest(
                                                    "c1", "ghost", json("{\"token\":\"t\"}"), null),
                                            1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("适配器未就绪")
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.CONNECTION_TYPE_UNKNOWN);
        }

        @Test
        @DisplayName("连接名重复应转成业务错误，而不是把 DuplicateKeyException 抛给前端")
        void shouldTranslateDuplicateName() {
            when(manager.connectionType("fake"))
                    .thenReturn(Optional.of(descriptor(Direction.FORWARD)));
            when(mapper.insert(any(WsConnection.class)))
                    .thenThrow(new DuplicateKeyException("dup"));

            assertThatThrownBy(
                            () ->
                                    service.create(
                                            new ConnectionCreateRequest(
                                                    "c1", "fake", json("{\"token\":\"t\"}"), null),
                                            1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.CONNECTION_NAME_EXISTS);
        }

        @Test
        @DisplayName("创建即启用：应立刻把运行时拉起来")
        void shouldStartRuntimeAfterCreate() {
            WsConnection[] holder = new WsConnection[1];
            stubInsertAndReload(holder);
            when(manager.connectionType("fake"))
                    .thenReturn(Optional.of(descriptor(Direction.FORWARD)));

            service.create(new ConnectionCreateRequest("c1", "fake", json("{}"), null), 1L);

            verify(manager).start(7L);
        }
    }

    @Nested
    @DisplayName("修改")
    class 修改 {

        @Test
        @DisplayName("config 里的掩码哨兵应被还原成库里原值，不能把密钥改写成星号")
        void shouldRestoreMaskedSecretOnUpdate() {
            WsConnection existing = new WsConnection();
            existing.setId(1L);
            existing.setName("c1");
            existing.setConnectionType("fake");
            existing.setEnabled(1);
            existing.setConfig("{\"token\":\"real-secret\"}");
            when(mapper.selectById(1L)).thenReturn(existing);
            when(manager.connectionType("fake"))
                    .thenReturn(Optional.of(descriptor(Direction.REVERSE)));

            service.update(
                    1L,
                    new ConnectionUpdateRequest(
                            "c1", json("{\"token\":\"" + ConfigMasking.MASK + "\"}"), null),
                    1L);

            verify(mapper)
                    .updateById(
                            org.mockito.ArgumentMatchers.<WsConnection>argThat(
                                    patch ->
                                            patch.getConfig().contains("real-secret")
                                                    && !patch.getConfig()
                                                            .contains(ConfigMasking.MASK)));
        }

        @Test
        @DisplayName("启用中的连接改配置后应通知运行时刷新")
        void shouldReloadWhenEnabled() {
            WsConnection existing = new WsConnection();
            existing.setId(1L);
            existing.setName("c1");
            existing.setConnectionType("fake");
            existing.setEnabled(1);
            existing.setConfig("{}");
            when(mapper.selectById(1L)).thenReturn(existing);
            when(manager.connectionType("fake"))
                    .thenReturn(Optional.of(descriptor(Direction.FORWARD)));

            service.update(1L, new ConnectionUpdateRequest("c1", json("{}"), null), 1L);

            verify(manager).reload(1L);
        }

        @Test
        @DisplayName("停用中的连接改配置不该碰运行时 —— 它本来就没在跑")
        void shouldNotReloadWhenDisabled() {
            WsConnection existing = new WsConnection();
            existing.setId(1L);
            existing.setName("c1");
            existing.setConnectionType("fake");
            existing.setEnabled(0);
            existing.setConfig("{}");
            when(mapper.selectById(1L)).thenReturn(existing);
            when(manager.connectionType("fake"))
                    .thenReturn(Optional.of(descriptor(Direction.FORWARD)));

            service.update(1L, new ConnectionUpdateRequest("c1", json("{}"), null), 1L);

            verify(manager, never()).reload(anyLong());
        }

        @Test
        @DisplayName("适配器不在线时改配置仍应成功，只是不做掩码还原")
        void shouldUpdateEvenWhenAdapterOffline() {
            WsConnection existing = new WsConnection();
            existing.setId(1L);
            existing.setName("c1");
            existing.setConnectionType("fake");
            existing.setEnabled(1);
            existing.setConfig("{\"token\":\"real\"}");
            when(mapper.selectById(1L)).thenReturn(existing);
            when(manager.connectionType("fake")).thenReturn(Optional.empty());

            service.update(
                    1L, new ConnectionUpdateRequest("c1", json("{\"token\":\"new\"}"), null), 1L);

            verify(mapper, times(1)).updateById(any(WsConnection.class));
        }
    }

    @Nested
    @DisplayName("启停与删除")
    class 启停与删除 {

        @Test
        @DisplayName("停用应先改库再停运行时")
        void shouldStopRuntimeOnDisable() {
            WsConnection existing = new WsConnection();
            existing.setId(1L);
            existing.setName("c1");
            existing.setConnectionType("fake");
            existing.setEnabled(1);
            existing.setConfig("{}");
            when(mapper.selectById(1L)).thenReturn(existing);

            service.setEnabled(1L, false, 1L);

            verify(mapper)
                    .updateById(
                            org.mockito.ArgumentMatchers.<WsConnection>argThat(
                                    p -> p.getEnabled() == 0));
            verify(manager).stop(1L);
            verify(manager, never()).start(anyLong());
        }

        @Test
        @DisplayName("删除应先摘运行时再删库 —— 反了会留下无主的重连任务")
        void shouldForgetRuntimeBeforeDelete() {
            WsConnection existing = new WsConnection();
            existing.setId(1L);
            when(mapper.selectById(1L)).thenReturn(existing);

            service.delete(1L);

            var inOrder = org.mockito.Mockito.inOrder(mapper, manager);
            inOrder.verify(mapper).selectById(1L);
            inOrder.verify(manager).forget(1L);
            inOrder.verify(mapper).deleteById(1L);
        }

        @Test
        @DisplayName("操作不存在的连接应报资源不存在")
        void shouldFailWhenNotFound() {
            when(mapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> service.get(99L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.CONNECTION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("查询")
    class 查询 {

        @Test
        @DisplayName("应答里的 config 必须已打掩码")
        void shouldMaskSecretInResponse() {
            WsConnection entity = new WsConnection();
            entity.setId(1L);
            entity.setName("c1");
            entity.setConnectionType("fake");
            entity.setEnabled(1);
            entity.setConfig("{\"token\":\"leak-me\"}");
            when(mapper.selectById(1L)).thenReturn(entity);
            when(manager.connectionType("fake"))
                    .thenReturn(Optional.of(descriptor(Direction.REVERSE)));

            ConnectionResponse response = service.get(1L);

            assertThat(response.config().toString()).doesNotContain("leak-me");
            assertThat(response.config().get("token").asString()).isEqualTo(ConfigMasking.MASK);
        }

        @Test
        @DisplayName("适配器不在线时应标记 typeReady=false，前端据此提示")
        void shouldMarkTypeNotReady() {
            WsConnection entity = new WsConnection();
            entity.setId(1L);
            entity.setConnectionType("ghost");
            entity.setEnabled(1);
            entity.setConfig("{}");
            when(mapper.selectById(1L)).thenReturn(entity);
            when(manager.connectionType("ghost")).thenReturn(Optional.empty());

            assertThat(service.get(1L).typeReady()).isFalse();
        }

        @Test
        @DisplayName("状态查询应先确认连接存在，避免为不存在的 ID 编造状态")
        void shouldCheckExistenceBeforeStatus() {
            when(mapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> service.status(99L)).isInstanceOf(BusinessException.class);
            verify(manager, never()).status(anyLong());
        }

        @Test
        @DisplayName("列表应把分页参数原样带回，供前端渲染分页器")
        void shouldEchoPaging() {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<WsConnection> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(2, 10);
            page.setTotal(35);
            page.setRecords(List.of());
            when(mapper.selectPage(
                            any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                            any()))
                    .thenReturn(page);

            var result = service.page(2, 10, null, null, null);

            assertThat(result.pageNum()).isEqualTo(2);
            assertThat(result.pageSize()).isEqualTo(10);
            assertThat(result.total()).isEqualTo(35);
            assertThat(result.pages()).isEqualTo(4);
        }
    }
}
