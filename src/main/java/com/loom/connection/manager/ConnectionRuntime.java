package com.loom.connection.manager;

import com.loom.connection.domain.ConnectionState;
import java.util.concurrent.ScheduledFuture;

/**
 * 单条连接的运行时状态。
 *
 * <p>包级私有 —— 外部只能通过 {@code ConnectionStatus} 快照读取， 避免运行态被随意改动。字段用 volatile 是因为它们会被 IPC 读取线程、
 * 调度线程、HTTP 线程同时访问。
 *
 * <h2>⚠️ 这里刻意<b>不用</b> Lombok</h2>
 *
 * <p>判断标准不是「能不能用」，而是「用了之后还剩什么」。这个类的情况：
 *
 * <ul>
 *   <li>字段全是包级可见：{@code ConnectionManager} 直接读写 {@code runtime.state}、 {@code runtime.handle}。写成私有
 *       + Lombok 访问器之后，代码反而是 {@code runtime.getState()} / {@code runtime.setState(...)} ——
 *       更长，且没带来封装 （同一个包，可见性本来就没变）。
 *   <li>这些字段是 {@code volatile} 的**并发协议**参与者。用访问器把它们藏起来， 反而让人看不清「哪些字段会被其他线程碰」—— 那是这个类里最需要一眼看到的信息。
 *   <li>剩下的四个状态迁移方法（{@code online}/{@code reconnecting}/…）是**有语义的**， 不是样板代码，Lombok 帮不上忙。
 * </ul>
 *
 * <p>结论：Lombok 的价值在于消除**机械重复**（一堆 getter/setter）， 而不是「让类看起来短」。这个类没有机械重复，所以不加。
 */
final class ConnectionRuntime {

    final long connectionId;
    final String name;
    final String connectionType;

    volatile String config;
    volatile String endpointPath;
    volatile boolean enabled;

    volatile ConnectionState state = ConnectionState.OFFLINE;
    volatile String failureReason;
    volatile int consecutiveFailures;

    /** 待执行的重连任务，停用时需要取消。 */
    volatile ScheduledFuture<?> pendingRetry;

    /** 当前活跃通道。反向连接在平台连入前为 null。 */
    volatile ConnectionHandle handle;

    /** 是否已向适配器发出过 conn.open，避免重复通知。 */
    volatile boolean adapterNotified;

    ConnectionRuntime(long connectionId, String name, String connectionType) {
        this.connectionId = connectionId;
        this.name = name;
        this.connectionType = connectionType;
    }

    /** 状态迁移到「等适配器」。 */
    void waitingAdapter() {
        this.state = ConnectionState.WAITING_ADAPTER;
        this.failureReason = "适配器未就绪（connectionType=" + connectionType + "）";
    }

    /** 状态迁移到「重连中」，并累加失败计数。 */
    void reconnecting(String reason) {
        this.state = ConnectionState.RECONNECTING;
        this.failureReason = reason;
        this.consecutiveFailures++;
    }

    /** 状态迁移到「在线」，清空失败信息。 */
    void online() {
        this.state = ConnectionState.ONLINE;
        this.failureReason = null;
        this.consecutiveFailures = 0;
    }

    void failed(String reason) {
        this.state = ConnectionState.FAILED;
        this.failureReason = reason;
    }

    void offline() {
        this.state = ConnectionState.OFFLINE;
        this.failureReason = null;
        this.consecutiveFailures = 0;
        this.adapterNotified = false;
    }
}
