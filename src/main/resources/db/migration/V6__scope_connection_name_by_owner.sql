-- 连接名只需在同一用户内唯一。旧索引把所有用户放在同一个全局命名空间，
-- 会导致不同用户无法使用相同的连接名，也与 owner_user_id 的隔离语义冲突。
ALTER TABLE `ws_connection`
    DROP INDEX `uk_name`,
    ADD UNIQUE KEY `uk_owner_name` (`owner_user_id`, `name`);
