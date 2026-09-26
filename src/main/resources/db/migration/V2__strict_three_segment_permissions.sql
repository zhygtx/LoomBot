-- 把旧的一段式超级权限串 '*' 升级为严格三段式 '*:*:*'。
--
-- 为什么不直接改 V1 里那行种子数据：
--   V1 已经落到开发库的 flyway_schema_history 里了。迁移文件一旦被应用就不可再变，
--   改它一个字符都会让校验和失配，Flyway 会直接拒绝启动（Validate failed:
--   Migration checksum mismatch for migration version 1），而且每个已经迁移过的库
--   都得手工 repair 一次。所以 V1 保持原样，变更放进这个新迁移 —— 新库（先跑 V1 再跑
--   V2）和老库（只补跑 V2）最终都是 '*:*:*'。
--
-- 两个 UPDATE 而不是一个，是为了不碰唯一键 uk_permission_perm：
--   若把 WHERE 写成 perm IN ('*', '*:*:*') 再统一 SET perm = '*:*:*'，在两个值同时存在时
--   会撞唯一键。先补名称/备注（不动 perm），再单独升级 perm，顺序上就不会冲突。
--
-- 两条语句都幂等：重复执行结果一致。

UPDATE sys_permission
   SET name = '超级权限',
       remark = '三段式 glob 通配全部权限，仅授予站长'
 WHERE perm IN ('*', '*:*:*');

UPDATE sys_permission
   SET perm = '*:*:*'
 WHERE perm = '*';
