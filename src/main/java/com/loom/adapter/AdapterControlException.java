package com.loom.adapter;

/** Adapter 控制 API 不可达或返回错误。 */
public class AdapterControlException extends RuntimeException {

    public AdapterControlException(String message) {
        super(message);
    }

    public AdapterControlException(String message, Throwable cause) {
        super(message, cause);
    }
}
