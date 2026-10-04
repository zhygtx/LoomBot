package com.loombot.plugin.storage;

/**
 * 插件存储的调用方错误。
 *
 * <p>这三个子类不是为了让 Java 内部好 catch，而是为了让内部接口能给出准确的 HTTP 状态码： 参数错误必须是 400（调用方改请求），版本冲突必须是 409（调用方重读再试），
 * 引用不存在必须是 404（调用方不要再重试）。三者混成 500 会让插件侧无法区分 "我写错了"和"宿主坏了"。
 */
public class PluginStorageException extends RuntimeException {

    public PluginStorageException(String message) {
        super(message);
    }

    /** 参数不合法：key 越界、作用域未知、作用域 id 含非法字符等。 */
    public static class InvalidRequest extends PluginStorageException {
        public InvalidRequest(String message) {
            super(message);
        }
    }

    /** 乐观锁版本不一致。 */
    public static class Conflict extends PluginStorageException {
        public Conflict(String message) {
            super(message);
        }
    }

    /** 引用的对象不存在。 */
    public static class NotFound extends PluginStorageException {
        public NotFound(String message) {
            super(message);
        }
    }
}
