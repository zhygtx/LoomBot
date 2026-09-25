package com.loom.runtime.ipc;

/**
 * IPC 通道的事件回调。
 *
 * <p>实现必须**快速返回** —— 回调运行在读取线程上，阻塞会卡住整条通道。 需要耗时处理时请自行投递到业务线程池。
 */
public interface IpcListener {

    /** 收到一条消息。 */
    void onMessage(IpcMessage message);

    /**
     * 通道关闭。
     *
     * @param cause {@code null} 表示正常关闭（本地主动 close 或对端 stdin/stdout 结束）
     */
    void onClosed(Throwable cause);
}
