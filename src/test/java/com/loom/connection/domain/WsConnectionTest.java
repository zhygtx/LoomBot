package com.loom.connection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 持久化实体的行为约束。
 *
 * <h2>为什么专门测 {@code toString}</h2>
 *
 * <p>{@code config} 里存着 bot token。实体出现在日志里（调试打印、异常上下文、 框架 trace）是常态，而 {@code toString}
 * 一旦把全部字段都输出，密钥就进了日志文件。
 *
 * <p>那是**永久性**泄漏：日志往往比数据库更容易被翻到，而且不会被「删除连接」带走。
 *
 * <h2>为什么不写成 ArchUnit 规则</h2>
 *
 * <p>试过，行不通。{@code @ToString(exclude = ...)} 是 {@code SOURCE} 保留的注解， 编译后字节码上看不到；而 Lombok 生成的方法体也不能被
 * ArchUnit 的调用图可靠表达。 与其绕圈子，不如直接测**那个真实后果** —— 密钥到底会不会出现在 toString 里。 这比规则更直接，也不依赖任何实现细节。
 */
@DisplayName("WsConnection 实体")
class WsConnectionTest {

    private static final String SECRET = "SUPER-SECRET-TOKEN-VALUE";

    private static WsConnection sample() {
        WsConnection entity = new WsConnection();
        entity.setId(1L);
        entity.setName("demo");
        entity.setConnectionType("fake");
        entity.setConfig("{\"token\":\"" + SECRET + "\"}");
        entity.setEndpointPath("/ws/abc");
        entity.setEnabled(1);
        return entity;
    }

    @Test
    @DisplayName("toString 绝不能带出 config 里的密钥 —— 那是永久性的日志泄漏")
    void toStringMustNotLeakSecret() {
        String text = sample().toString();

        assertThat(text)
                .as("实体打印到日志时，config 的内容（含 token）必须不出现")
                .doesNotContain(SECRET)
                .doesNotContain("token");
        // 但其他字段应当在，否则排查问题时看不出是哪条连接
        assertThat(text).contains("demo").contains("fake");
    }

    @Test
    @DisplayName("toString 之外，getConfig 仍必须能取到值 —— 掩码是输出层的事，不是数据层的")
    void configMustStillBeReadable() {
        assertThat(sample().getConfig()).contains(SECRET);
    }

    @Test
    @DisplayName("应具备无参构造器与全部字段的读写访问器 —— MyBatis-Plus 回填字段的前提")
    void shouldBeAMutableDataCarrier() throws Exception {
        // 无参构造器
        assertThat(WsConnection.class.getDeclaredConstructor()).isNotNull();

        // 每个字段都要有 getter 与 setter，否则 MyBatis-Plus 映射不上且不报错
        List<String> fields =
                Arrays.stream(WsConnection.class.getDeclaredFields())
                        .filter(field -> !field.isSynthetic())
                        .map(java.lang.reflect.Field::getName)
                        .toList();

        assertThat(fields).isNotEmpty();
        for (String field : fields) {
            String suffix = Character.toUpperCase(field.charAt(0)) + field.substring(1);
            Method getter = WsConnection.class.getMethod("get" + suffix);
            Method setter = WsConnection.class.getMethod("set" + suffix, getter.getReturnType());
            assertThat(getter).isNotNull();
            assertThat(setter).isNotNull();
        }
    }

    @Test
    @DisplayName("字段数量应与表结构一致 —— 防止加了列却忘了加字段")
    void fieldCountShouldMatchTable() {
        List<String> fields =
                Arrays.stream(WsConnection.class.getDeclaredFields())
                        .filter(field -> !field.isSynthetic())
                        .map(java.lang.reflect.Field::getName)
                        .sorted()
                        .toList();

        // 与 V2__init_ws_connection.sql 的列一一对应
        assertThat(fields)
                .containsExactly(
                        "config",
                        "connectionType",
                        "createBy",
                        "createTime",
                        "deleted",
                        "enabled",
                        "endpointPath",
                        "id",
                        "name",
                        "ownerUserId",
                        "remark",
                        "updateBy",
                        "updateTime");
    }
}
