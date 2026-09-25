package com.loom.connection.adapter;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 适配器向 Java 发起的操作的委派接口。
 *
 * <p>存在的意义：{@link AdapterSession} 负责**协议分发**，但真正的传输操作 （建连、发帧、关通道）属于 {@code
 * ConnectionManager}。用这个接口把两者解耦， 避免会话直接依赖管理器造成循环依赖。
 *
 * <p>实现必须快速返回 —— 这些方法运行在 IPC 读取线程上。
 */
public interface AdapterEvents {

    /** 适配器上报 {@code hello}，元信息就绪。 */
    void onHello(AdapterSession session);

    /**
     * 适配器上抛「命中触发条件」的事件。
     *
     * <p>注意：**未命中任何触发条件的数据不会走到这里** —— 过滤发生在适配器内部 （倒排索引未命中直接丢弃）。所以本方法只在真正需要执行工作流时被调用。
     */
    void onEventMatched(
            AdapterSession session, long connectionId, String eventType, JsonNode event);

    /**
     * 适配器请求建立正向连接。
     *
     * @return 成功时返回 handleId；失败返回 {@code null}（原因由本方法自行记录）
     */
    String openForward(
            AdapterSession session, long connectionId, String url, Map<String, String> headers);

    /** 适配器请求在通道上发送数据。 */
    void handleSend(AdapterSession session, String handleId, String encoding, String content);

    /** 适配器请求关闭通道。 */
    void handleClose(AdapterSession session, String handleId, int code, String reason);

    /** 适配器进程退出（非主动关闭）。管理器据此做退避重启。 */
    void onAdapterExited(AdapterSession session, int exitCode);
}
