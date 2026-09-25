package com.loom.common.api;

/**
 * 全局错误码。
 *
 * <p>码段约定 —— 便于从错误码直接定位来源模块：
 *
 * <ul>
 *   <li>0 —— 成功
 *   <li>1xxxxx —— 通用 / 框架层
 *   <li>2xxxxx —— 认证授权
 *   <li>3xxxxx —— 系统管理（用户 / 角色 / 权限）
 *   <li>4xxxxx —— 插件
 *   <li>5xxxxx —— 工作流
 *   <li>6xxxxx —— 连接
 * </ul>
 *
 * <p>新增模块时按段位递增。这样线上看到一个 {@code 400002} 就能立刻知道是插件模块的问题， 不需要去翻代码或全局搜索。
 */
public enum ErrorCode {
    SUCCESS(0, "成功"),

    // ---------- 1xxxxx 通用 ----------
    BAD_REQUEST(100000, "请求参数不合法"),
    RESOURCE_NOT_FOUND(100001, "资源不存在"),
    METHOD_NOT_ALLOWED(100002, "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE(100003, "不支持的媒体类型"),
    REQUEST_BODY_UNREADABLE(100004, "请求体格式错误"),
    INTERNAL_ERROR(100500, "系统内部错误"),

    // ---------- 2xxxxx 认证授权 ----------
    UNAUTHORIZED(200000, "未登录或登录已过期"),
    FORBIDDEN(200001, "没有操作权限"),
    LOGIN_FAILED(200002, "用户名或密码错误"),
    ACCOUNT_DISABLED(200003, "账号已被停用"),

    // ---------- 3xxxxx 系统管理 ----------
    USER_NOT_FOUND(300000, "用户不存在"),
    USERNAME_EXISTS(300001, "用户名已存在"),
    ROLE_NOT_FOUND(300002, "角色不存在"),
    ROLE_CODE_EXISTS(300003, "角色标识已存在"),
    BUILTIN_ROLE_READONLY(300004, "内置角色不允许修改或删除"),
    PERMISSION_NOT_FOUND(300005, "权限不存在"),
    PERMISSION_CODE_EXISTS(300006, "权限串已存在"),

    // ---------- 4xxxxx 插件 ----------
    PLUGIN_NOT_FOUND(400000, "插件不存在"),
    PLUGIN_ALREADY_RUNNING(400001, "插件已在运行"),
    PLUGIN_START_FAILED(400002, "插件启动失败"),
    PLUGIN_UPLOAD_FAILED(400003, "插件上传失败"),

    // ---------- 5xxxxx 工作流 ----------
    WORKFLOW_NOT_FOUND(500000, "工作流不存在"),
    WORKFLOW_DEFINITION_INVALID(500001, "工作流定义不合法"),
    WORKFLOW_ALREADY_RUNNING(500002, "工作流正在运行"),

    // ---------- 6xxxxx 连接 ----------
    CONNECTION_NOT_FOUND(600000, "连接不存在"),
    CONNECTION_NAME_EXISTS(600001, "连接名已存在"),
    CONNECTION_UNAVAILABLE(600002, "连接不可用");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
