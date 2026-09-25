package com.loom.runtime.ipc;

/** IPC 协议违规。表示对端发的不是合法消息，或字段缺失。 */
public class IpcProtocolException extends RuntimeException {

    public IpcProtocolException(String message) {
        super(message);
    }

    public IpcProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
