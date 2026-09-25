package com.loom.connection.handshake;

/**
 * 握手校验器。
 *
 * <p>实现必须是**纯函数**（无状态、无副作用），这样既好测试，也能被多线程并发调用 —— 握手可能同时来自多个平台连接。
 */
public interface HandshakeValidator {

    /** 本校验器处理的 {@code x-handshake.mode} 值。 */
    String mode();

    /**
     * 校验。
     *
     * @param spec 插件声明的规格
     * @param secret 从 config 里按 {@code secretField} 取出的密钥值；{@code none} 模式为 {@code null}
     * @param request 握手请求
     * @return {@code null} 表示通过；否则返回**拒绝原因**（会记录到日志，注意别把密钥写进去）
     */
    String failureReason(HandshakeSpec spec, String secret, HandshakeRequest request);
}
