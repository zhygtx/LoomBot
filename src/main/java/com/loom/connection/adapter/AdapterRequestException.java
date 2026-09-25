package com.loom.connection.adapter;

/** 向适配器发起的请求失败（超时 / 通道断开 / 适配器返回 ok:false）。 */
public class AdapterRequestException extends RuntimeException {

    public AdapterRequestException(String message) {
        super(message);
    }

    public AdapterRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
