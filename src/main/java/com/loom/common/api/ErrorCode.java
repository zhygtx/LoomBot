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
    // 刻意不写成「邮箱不存在」与「密码错误」两条 —— 那等于提供一个免费的
    // 「这个邮箱注册过没有」查询接口。两种情况共用一个码与一句文案。
    LOGIN_FAILED(200002, "邮箱或密码错误"),
    ACCOUNT_DISABLED(200003, "账号已被停用"),
    EMAIL_CODE_INVALID(200004, "验证码错误或已过期"),
    EMAIL_CODE_TOO_FREQUENT(200005, "验证码发送过于频繁，请稍后再试"),
    EMAIL_CODE_ATTEMPTS_EXCEEDED(200006, "验证码错误次数过多，请重新获取"),
    REGISTRATION_DISABLED(200007, "系统当前未开放注册"),
    LOGIN_DISABLED(200008, "系统当前暂停登录"),
    EMAIL_CODE_DISABLED(200009, "系统当前暂停发送验证码"),
    PASSWORD_RESET_DISABLED(200010, "系统当前暂停找回密码"),

    // ---------- 3xxxxx 系统管理 ----------
    USER_NOT_FOUND(300000, "用户不存在"),
    ROLE_NOT_FOUND(300002, "角色不存在"),
    ROLE_CODE_EXISTS(300003, "角色标识已存在"),
    BUILTIN_ROLE_READONLY(300004, "内置角色不允许修改或删除"),
    PERMISSION_NOT_FOUND(300005, "权限不存在"),
    PERMISSION_CODE_EXISTS(300006, "权限串已存在"),
    EMAIL_EXISTS(300007, "邮箱已被注册"),
    SYSTEM_CONFIG_NOT_FOUND(300008, "系统配置不存在"),
    SYSTEM_CONFIG_VALUE_INVALID(300009, "系统配置值不合法"),
    MENU_NOT_FOUND(300010, "菜单不存在"),
    MENU_ROUTE_EXISTS(300011, "菜单路由名称已存在"),
    MENU_HAS_CHILDREN(300012, "菜单存在子节点，不能直接删除"),

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
    CONNECTION_UNAVAILABLE(600002, "连接不可用"),
    CONNECTION_TYPE_UNKNOWN(600003, "找不到已登记的适配器版本或连接类型"),
    CONNECTION_CONFIG_INVALID(600004, "连接参数不是合法的 JSON 对象"),
    CONNECTION_TYPE_IMMUTABLE(600005, "连接类型创建后不可修改，请删除后重建"),
    CONNECTION_IN_USE(600006, "连接正在被工作流使用");

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
