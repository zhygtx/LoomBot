package com.loom.connection.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * WS 连接定义。
 *
 * <h2>两类字段的归属划分（这是本表设计的核心）</h2>
 *
 * <table border="1">
 *   <caption>字段归属</caption>
 *   <tr><th>字段</th><th>归属</th><th>说明</th></tr>
 *   <tr><td>{@code config}</td><td><b>插件</b></td>
 *       <td>协议特有参数（AppID / token / …），opaque，Java 不解释字段含义</td></tr>
 *   <tr><td>{@code endpointPath}</td><td><b>Java</b></td>
 *       <td>传输层接入地址，Java 生成、用户不可改；正向连接为 {@code null}</td></tr>
 * </table>
 *
 * <p>这个划分不是「协议假设」：Java 持有 WS 服务器，接入地址本来就归它管。
 *
 * <h2>为什么 config 是 String 而不是 JsonNode</h2>
 *
 * <p>MySQL 的 {@code JSON} 列通过 JDBC 以字符串形式读写，用 {@code String} 可以直接映射， <b>不需要自定义
 * TypeHandler</b>。需要用的时候再解析（比如按插件 schema 的 {@code x-secret} 字段做掩码）。
 *
 * <p><b>注意这只对持久化成立，对 API 不成立</b>：对外的 DTO 用的是 {@code JsonNode} （见 {@code
 * ConnectionCreateRequest}），因为前端按 schema 渲染出的表单值天然是对象。 转换发生在 Service 落库那一刻。
 *
 * <h2>为什么用 Lombok 而不是手写 getter/setter</h2>
 *
 * <p>这是全工程唯一一个「纯数据 + 需要可变」的类 —— MyBatis-Plus 的实体必须有无参构造器 和 setter 才能回填字段，所以它不能用 {@code
 * record}。手写的结果是 165 行里只有 13 行是 字段声明，其余全是机械的读写方法。
 *
 * <p>用 {@code @Getter/@Setter} 之后剩下的每一行都是**有意义**的：字段名、类型、注释。 {@code @ToString} 的 {@code exclude}
 * 是必须的 —— 见下方说明。
 *
 * <h2>没有 {@code deleted}：删除就是真删</h2>
 *
 * <p>逻辑删除要求每条查询都记得加「未删除」，漏了不报错；而且它和 {@code uk_connection_owner_name} / {@code
 * uk_connection_endpoint} 打架 —— 删掉的连接还占着名字和接入路径，于是「删了却建不回来」。 理由完整版见 {@code
 * V1__bootstrap_schema.sql} 末尾。运行时侧的对应处理是：真删之后 {@code ConnectionManager.forget(id)} 必须清掉状态投影，避免留下
 * {@code connection_definition} 中不存在的孤儿状态行。
 *
 * <p>注意 {@code enabled} <b>保留</b>：它不是「这行记录还算不算数」，而是「这条连接现在跑不跑」， 是人的意图，和运行时状态机是两件事。
 */
@TableName("connection_definition")
@Getter
@Setter
// config 里存着 bot token。日志里出现实体是很常见的（调试、异常上下文），
// 默认的 toString 会把密钥原样打进日志文件 —— 那是永久性的泄漏，
// 而且日志往往比数据库更容易被翻到。所以这里显式排除它。
@ToString(exclude = "config")
public class WsConnection {

    @TableId private Long id;

    private String name;

    /** 连接固定使用的适配器插件版本。 */
    private Long pluginVersionId;

    /** 连接类型，来自适配器声明。决定方向、配置表单、路由到哪个适配器。 */
    private String connectionType;

    /** 协议特有参数，opaque JSON 文本。{@code toString} 已排除，避免密钥进日志。 */
    private String config;

    /** 反向接入路径，Java 生成。正向连接为 {@code null}。 */
    private String endpointPath;

    private Long ownerUserId;

    /** 配置状态：1=启用 0=停用。这是**人的意图**，与运行时状态无关。 */
    private Integer enabled;

    /** 期望状态版本。配置、启停或端点变化时递增，Adapter 用它判断是否需要重载。 */
    private Long desiredRevision;

    private String remark;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;
}
