package com.loom.connection;

import com.loom.connection.manager.ConnectionManager;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试的清理工具。
 *
 * <h2>为什么必须物理删除：{@code @TableLogic} 的陷阱</h2>
 *
 * <p>{@code ws_connection} 用的是逻辑删除（{@code @TableLogic}）。所以 {@code mapper.deleteById(...)} 只是把
 * {@code deleted} 置 1，**行仍然留在表里**。 而 MyBatis-Plus 会给所有查询自动加上 {@code deleted = 0}，于是：
 *
 * <pre>
 *   第一次清理：deleteById → deleted=1 → 「删掉了」
 *   下次清理：selectList 查不到它（被 deleted=0 过滤）→ 再也删不掉
 * </pre>
 *
 * <p>表现就是**每次跑集成测试都往库里堆一批永久残留的脏数据**，而且越积越多， 还很难看出是谁留下的（名字都是 UUID）。
 *
 * <p>测试要的是「把现场恢复原样」，逻辑删除做不到这一点，所以这里直接走 SQL 物理删除。 这不是绕过业务逻辑 —— 业务上的删除语义由 Service 保证，测试清理是另一回事。
 */
final class TestCleanup {

    private TestCleanup() {}

    /**
     * 按连接名前缀物理删除测试数据，并摘掉对应的运行时。
     *
     * <p>用前缀而不是精确名，是为了顺带清掉**历史遗留**的那些（比如以前跑失败留下的）。
     */
    static void purge(JdbcTemplate jdbc, ConnectionManager manager, String namePrefix) {
        // 先摘运行时：否则被删掉的连接可能还挂着重连定时任务
        jdbc.queryForList(
                        "SELECT id FROM ws_connection WHERE name LIKE ?",
                        Long.class,
                        namePrefix + "%")
                .forEach(manager::forget);
        jdbc.update("DELETE FROM ws_connection WHERE name LIKE ?", namePrefix + "%");
    }
}
