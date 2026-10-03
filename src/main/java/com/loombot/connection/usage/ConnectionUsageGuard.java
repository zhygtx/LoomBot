package com.loombot.connection.usage;

import java.util.List;

/**
 * 连接删除前后的引用检查与派生数据清理。
 *
 * <p>连接模块自己不认识工作流定义，但连接被工作流引用时删掉会让那些工作流**静默失效**（事件进不来、列表里还显示已启用），
 * 所以删除前必须问一声。接口放在连接模块、实现放在工作流模块，避免两个包互相依赖。
 */
public interface ConnectionUsageGuard {

    /** 正在使用该连接的工作流名称；返回空表示可以安全删除。 */
    List<String> workflowsUsing(long connectionId, Long ownerUserId);

    /** 连接已删除：清理触发索引等派生数据。 */
    void onConnectionDeleted(long connectionId);
}
